package com.repolens.kernel.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.kernel.persistence.entity.RkVerificationRunEntity;
import com.repolens.kernel.persistence.mapper.RkVerificationRunMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 在**影子工作区**里真跑构建/测试，闭合"改→编译/测试→读真错→自愈"环。
 *
 * <p>三条硬约束（防 DeepSeek 式假实现）：
 * <ol>
 *   <li><b>验的是自己的改动</b>：{@code shadowId} 落库，可自证验证对象是 agent 刚写的影子区，非真目录。</li>
 *   <li><b>断网执行</b>：macOS 下用 {@code sandbox-exec} 拒绝出网 + 构建命令一律离线，
 *       防 reward hacking（联网下载"通过"结果/篡改 oracle）。做不到隔离就如实记 {@code networkIsolated=false}，绝不谎报。</li>
 *   <li><b>探测不到构建体系不算通过</b>：返回 {@code passed=false} 并附原因，交给 {@link com.repolens.kernel.ledger.FeatureLedgerService} 拒绝发绿灯。</li>
 * </ol>
 */
@Slf4j
@Service
public class VerificationRunner {

    /** 仅拒绝出网：既挡住 reward hacking，又不误伤本地文件/回环。 */
    private static final String DENY_NETWORK_PROFILE = "(version 1)(allow default)(deny network-outbound)";
    private static final int OUTPUT_TAIL_LIMIT = 8000;

    private final VerificationOutputParser parser;
    private final RkVerificationRunMapper verificationRunMapper;
    private final ObjectMapper objectMapper;

    /** 单次验证超时（秒），编译/测试默认 5min，可注入覆盖。 */
    private long timeoutSeconds = 300;

    public VerificationRunner(VerificationOutputParser parser,
                              RkVerificationRunMapper verificationRunMapper,
                              ObjectMapper objectMapper) {
        this.parser = parser;
        this.verificationRunMapper = verificationRunMapper;
        this.objectMapper = objectMapper;
    }

    public void setTimeoutSeconds(long timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public enum Kind { COMPILE, TEST }

    /**
     * 在影子区跑一次验证并落库。
     *
     * @param shadowDir 影子工作区根目录（验证在此执行，非真仓库目录）
     */
    public VerificationOutcome verify(Long repoId, Long sessionId, Long runId, Long shadowId,
                                      Path shadowDir, Kind kind) {
        Optional<BuildTarget> targetOpt = BuildTarget.detect(shadowDir);
        if (targetOpt.isEmpty()) {
            List<Failure> fs = List.of(new Failure("", 0, "",
                    "无法在影子区探测到构建体系(pom.xml/build.gradle/package.json/...)，无法验证——按未通过处理。", ""));
            VerificationOutcome outcome = new VerificationOutcome(null, "unknown", kind.name(),
                    -1, false, "", fs, false);
            return persist(repoId, sessionId, runId, shadowId, outcome);
        }

        BuildTarget target = targetOpt.get();
        List<String> buildCmd = kind == Kind.COMPILE ? target.compileCommand() : target.testCommand();
        boolean isolate = sandboxAvailable();

        Exec exec = runProcess(shadowDir, buildCmd, isolate);
        List<Failure> failures = parser.parse(exec.output, target, shadowDir);
        // 通过 = 退出码 0 且未解析出失败；退出码 0 却有失败（罕见的解析器过敏）时以退出码为准但保留失败供审阅
        boolean passed = exec.exitCode == 0;

        VerificationOutcome outcome = new VerificationOutcome(null, target.code(), kind.name(),
                exec.exitCode, passed, tail(exec.output), failures, isolate);
        return persist(repoId, sessionId, runId, shadowId, outcome);
    }

    private VerificationOutcome persist(Long repoId, Long sessionId, Long runId, Long shadowId,
                                        VerificationOutcome outcome) {
        RkVerificationRunEntity e = new RkVerificationRunEntity();
        e.setRepoId(repoId);
        e.setSessionId(sessionId);
        e.setRunId(runId);
        e.setShadowId(shadowId);
        e.setWorkDir("SHADOW");
        e.setBuildTarget(outcome.buildTarget());
        e.setKind(outcome.kind());
        e.setExitCode(outcome.exitCode());
        e.setPassed(outcome.passed());
        e.setOutputTail(outcome.outputTail());
        e.setFailuresJson(toJson(outcome.failures()));
        e.setNetworkIsolated(outcome.networkIsolated());
        e.setOracleTampered(false);
        try {
            verificationRunMapper.insert(e);
        } catch (Exception ex) {
            log.warn("[verify] 验证结果落库失败(不阻断验证语义): {}", ex.getMessage());
        }
        return outcome.withVerificationId(e.getId());
    }

    private Exec runProcess(Path workDir, List<String> buildCmd, boolean isolate) {
        String joined = String.join(" ", buildCmd);
        List<String> argv = new ArrayList<>();
        if (isolate) {
            argv.add("sandbox-exec");
            argv.add("-p");
            argv.add(DENY_NETWORK_PROFILE);
        }
        argv.add("/bin/sh");
        argv.add("-c");
        argv.add(joined);

        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        // 离线兜底环境（配合命令自带的 -o/--offline）
        pb.environment().put("GOPROXY", "off");
        pb.environment().put("GOFLAGS", "-mod=mod");

        try {
            Process p = pb.start();
            CompletableFuture<String> reader = CompletableFuture.supplyAsync(() -> readAll(p));
            boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                String partial = reader.getNow("");
                log.warn("[verify] 验证超时 {}s，已强杀: {}", timeoutSeconds, joined);
                return new Exec(-1, partial + "\n[verify] TIMEOUT after " + timeoutSeconds + "s");
            }
            return new Exec(p.exitValue(), reader.get());
        } catch (Exception e) {
            log.warn("[verify] 验证进程执行异常: {}", e.getMessage());
            return new Exec(-1, "[verify] process error: " + e.getMessage());
        }
    }

    private String readAll(Process p) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Exception e) {
            sb.append("[verify] read error: ").append(e.getMessage());
        }
        return sb.toString();
    }

    private boolean sandboxAvailable() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("mac") && Path.of("/usr/bin/sandbox-exec").toFile().exists();
    }

    private String tail(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= OUTPUT_TAIL_LIMIT ? s : s.substring(s.length() - OUTPUT_TAIL_LIMIT);
    }

    private String toJson(List<Failure> failures) {
        try {
            return objectMapper.writeValueAsString(failures);
        } catch (Exception e) {
            return "[]";
        }
    }

    private record Exec(int exitCode, String output) {}
}
