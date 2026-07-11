package com.repolens.kernel.tools;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * <b>真持久</b> shell：每个会话一条长活的 {@code bash} 进程，{@code cd}/{@code export} 的 cwd/env
 * 跨多次 {@link #exec} 调用存活——这是它与旧实现（每次 {@code bash -c} 新起进程、状态不留、
 * {@code kill} 因 map 从未 put 永远失效的"假持久 + 死 kill"）的根本区别。
 *
 * <p>命令完成检测走<b>哨兵协议</b>：每条命令后追加 {@code printf '\n<nonce>:<exit>\n'}，
 * 读 stdout 直到哨兵行，即拿到该命令的真实退出码；命令跑在<b>主 shell</b>（{@code { …; }} 分组而非子 shell），
 * 故 {@code export} 真的落到会话环境。
 *
 * <p>三个旧坑的针对性修复：
 * <ol>
 *   <li><b>持久</b>：单 bash 长活，状态跨调用留存（旧版每次新进程，env 必丢）。</li>
 *   <li><b>超时只杀子树、不杀壳</b>：超时时用 {@code pgrep -P} 递归收集 bash 的后代进程并 SIGKILL，
 *       bash 本身存活、会话不断（旧版 {@code destroyForcibly} 连壳一起杀，且 orphan 子进程还在跑）。</li>
 *   <li><b>后台 + 大输出转盘</b>：{@code runInBackground} 把命令挂到日志文件后台跑、立即返回句柄；
 *       前台输出 >30k 转磁盘只回预览（旧版都没有）。</li>
 * </ol>
 */
@Slf4j
@Component("kernelPersistentShell")
public class PersistentShell {

    /** 前台单条命令默认超时 3min（agent 常用区间 2–10min），上限 10min。下限放到 1s：
     *  "2–10min"是给 agent 选值的指南，不是硬地板——快命令该能设小超时快速失败。 */
    static final long DEFAULT_TIMEOUT_SEC = 180;
    static final long MIN_TIMEOUT_SEC = 1;
    static final long MAX_TIMEOUT_SEC = 600;
    /** 前台输出超过此阈值转磁盘、只回预览。 */
    static final int INLINE_OUTPUT_LIMIT = 30_000;

    private final CommandSafetyChecker safetyChecker;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public PersistentShell(CommandSafetyChecker safetyChecker) {
        this.safetyChecker = safetyChecker;
    }

    /** 一次前台执行结果。{@code overflowPath} 非空表示完整输出已转磁盘、{@code output} 只是预览。 */
    public record ExecResult(String output, int exitCode, boolean timedOut,
                             boolean blocked, String blockReason, Path overflowPath) {
        public static ExecResult blocked(String reason) {
            return new ExecResult("", -1, false, true, reason, null);
        }
    }

    /** 后台任务句柄：进程在日志文件里跑，用 {@link #backgroundOutput} 取增量输出。 */
    public record BackgroundHandle(String bgId, long pid, Path logFile) {}

    /**
     * 在会话持久 shell 里前台执行一条命令，阻塞到完成或超时。
     *
     * @param sessionId  会话隔离键（同键复用同一条 bash，env/cwd 因此存活）
     * @param workdir    首次为该会话建 shell 时的初始工作目录
     * @param command    要执行的命令（可含管道/重定向/多语句）
     * @param timeoutSec 超时秒数，钳到 [120,600]
     */
    public synchronized ExecResult exec(String sessionId, Path workdir, String command, long timeoutSec) {
        String reason = safetyChecker.check(command);
        if (reason != null) {
            return ExecResult.blocked(reason);
        }
        long timeout = Math.max(MIN_TIMEOUT_SEC, Math.min(MAX_TIMEOUT_SEC, timeoutSec));
        Session s;
        try {
            s = session(sessionId, workdir);
        } catch (IOException e) {
            return new ExecResult("[shell 启动失败] " + e.getMessage(), -1, false, false, null, null);
        }

        String nonce = "__RK_DONE_" + UUID.randomUUID().toString().replace("-", "") + "__";
        try {
            // { …; } 保持在主 shell 执行 → export/cd 落到会话；哨兵带真实 $?
            s.stdin.write("{ " + command + " ; } 2>&1 ; printf '\\n" + nonce + "%d\\n' \"$?\"\n");
            s.stdin.flush();
        } catch (IOException e) {
            dropSession(sessionId, s);
            return new ExecResult("[shell 写入失败，会话已重置] " + e.getMessage(), -1, false, false, null, null);
        }

        StringBuilder out = new StringBuilder();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
        Integer exit = null;
        boolean timedOut = false;
        try {
            while (true) {
                if (System.nanoTime() > deadline) {
                    timedOut = true;
                    killDescendants(s.process.pid());
                    // 杀掉子树后 bash 会补出哨兵行；再兜底读一小会
                    exit = drainForSentinel(s, nonce, out, System.nanoTime() + TimeUnit.SECONDS.toNanos(10));
                    break;
                }
                // 非阻塞轮询：命令无输出时（如 sleep）readLine 会一直阻塞，
                // 必须用 ready() 让出控制、回到上面检查 deadline，否则超时永不触发（旧坑）。
                if (!s.readerReady()) {
                    sleepQuiet();
                    continue;
                }
                String line = s.reader.readLine();
                if (line == null) {
                    // shell EOF：进程死了，重置会话
                    dropSession(sessionId, s);
                    return new ExecResult(out + "\n[shell 意外退出，会话已重置]", -1, false, false, null, null);
                }
                int idx = line.indexOf(nonce);
                if (idx >= 0) {
                    if (idx > 0) {
                        out.append(line, 0, idx);
                    }
                    exit = parseExit(line.substring(idx + nonce.length()));
                    break;
                }
                out.append(line).append('\n');
            }
        } catch (IOException e) {
            dropSession(sessionId, s);
            return new ExecResult(out + "\n[shell 读取失败，会话已重置] " + e.getMessage(), -1, false, false, null, null);
        }

        int exitCode = timedOut ? 124 : (exit != null ? exit : -1);
        String full = out.toString();
        if (timedOut) {
            full = full + "\n[超时 " + timeout + "s，已杀掉子进程树，shell 会话保留]";
        }
        return materialize(full, exitCode, timedOut);
    }

    /** 后台执行：命令挂到日志文件、立即返回句柄，不阻塞会话。 */
    public synchronized BackgroundHandle runInBackground(String sessionId, Path workdir, String command) {
        String reason = safetyChecker.check(command);
        if (reason != null) {
            throw new IllegalArgumentException(reason);
        }
        Session s;
        try {
            s = session(sessionId, workdir);
            String bgId = UUID.randomUUID().toString().substring(0, 8);
            Path log = Files.createTempFile("rk-bg-" + bgId + "-", ".log");
            String nonce = "__RK_BGPID_" + bgId + "__";
            // 后台起任务，回显子进程 PID 供后续管理
            s.stdin.write("{ " + command + " ; } > '" + log + "' 2>&1 & printf '"
                    + nonce + "%d\\n' \"$!\"\n");
            s.stdin.flush();
            long pid = readPid(s, nonce);
            return new BackgroundHandle(bgId, pid, log);
        } catch (IOException e) {
            throw new IllegalStateException("后台任务启动失败: " + e.getMessage(), e);
        }
    }

    /** 读后台任务日志文件当前内容（供轮询增量输出）。 */
    public String backgroundOutput(BackgroundHandle handle) {
        try {
            return Files.exists(handle.logFile()) ? Files.readString(handle.logFile()) : "";
        } catch (IOException e) {
            return "[读后台日志失败] " + e.getMessage();
        }
    }

    /** 关闭并丢弃某会话的 shell（下次 exec 会重建）。 */
    public synchronized void close(String sessionId) {
        Session s = sessions.remove(sessionId);
        if (s != null) {
            s.destroy();
        }
    }

    @PreDestroy
    public synchronized void shutdown() {
        sessions.values().forEach(Session::destroy);
        sessions.clear();
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    private Session session(String sessionId, Path workdir) throws IOException {
        Session existing = sessions.get(sessionId);
        if (existing != null && existing.process.isAlive()) {
            return existing;
        }
        if (existing != null) {
            sessions.remove(sessionId, existing);
        }
        // --norc --noprofile：无 rc 噪声；从管道读非交互，不打印 PS1
        ProcessBuilder pb = new ProcessBuilder("/bin/bash", "--norc", "--noprofile");
        if (workdir != null) {
            pb.directory(workdir.toFile());
        }
        pb.redirectErrorStream(true);
        Process p = pb.start();
        Session s = new Session(p);
        sessions.put(sessionId, s);
        return s;
    }

    private void dropSession(String sessionId, Session s) {
        sessions.remove(sessionId, s);
        s.destroy();
    }

    /** 超时后兜底读哨兵，读到就返回退出码，否则 null。 */
    private Integer drainForSentinel(Session s, String nonce, StringBuilder out, long deadline) throws IOException {
        while (System.nanoTime() < deadline) {
            if (!s.readerReady()) {
                sleepQuiet();
                continue;
            }
            String line = s.reader.readLine();
            if (line == null) {
                return null;
            }
            int idx = line.indexOf(nonce);
            if (idx >= 0) {
                if (idx > 0) {
                    out.append(line, 0, idx);
                }
                return parseExit(line.substring(idx + nonce.length()));
            }
            out.append(line).append('\n');
        }
        return null;
    }

    private long readPid(Session s, String nonce) throws IOException {
        String line;
        while ((line = s.reader.readLine()) != null) {
            int idx = line.indexOf(nonce);
            if (idx >= 0) {
                Integer pid = parseExit(line.substring(idx + nonce.length()));
                return pid != null ? pid : -1;
            }
        }
        return -1;
    }

    /** 递归收集并 SIGKILL bash 的后代进程；<b>绝不动 bash 自身</b>（会话得以存活）。 */
    private void killDescendants(long shellPid) {
        List<Long> victims = new ArrayList<>();
        collectDescendants(shellPid, victims);
        for (Long pid : victims) {
            try {
                new ProcessBuilder("kill", "-9", String.valueOf(pid))
                        .redirectErrorStream(true).start().waitFor(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // 尽力而为
            }
        }
        if (!victims.isEmpty()) {
            log.warn("[shell] 超时杀子进程树 {} 个（保留壳 pid={}）", victims.size(), shellPid);
        }
    }

    private void collectDescendants(long pid, List<Long> acc) {
        try {
            Process pg = new ProcessBuilder("pgrep", "-P", String.valueOf(pid)).start();
            List<Long> children = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(pg.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) {
                    l = l.trim();
                    if (!l.isEmpty()) {
                        children.add(Long.parseLong(l));
                    }
                }
            }
            pg.waitFor(2, TimeUnit.SECONDS);
            for (Long c : children) {
                collectDescendants(c, acc);  // 先收孙辈
                acc.add(c);
            }
        } catch (Exception ignored) {
            // pgrep 不可用/无子进程：忽略
        }
    }

    /** 前台输出 >30k 转磁盘，只回 head/tail 预览 + overflowPath。 */
    private ExecResult materialize(String full, int exitCode, boolean timedOut) {
        if (full.length() <= INLINE_OUTPUT_LIMIT) {
            return new ExecResult(full, exitCode, timedOut, false, null, null);
        }
        try {
            Path overflow = Files.createTempFile("rk-shell-out-", ".log");
            Files.writeString(overflow, full);
            int keep = INLINE_OUTPUT_LIMIT / 2;
            String preview = full.substring(0, keep)
                    + "\n… [输出 " + full.length() + " 字符，已转磁盘 " + overflow + "] …\n"
                    + full.substring(full.length() - keep);
            return new ExecResult(preview, exitCode, timedOut, false, null, overflow);
        } catch (IOException e) {
            return new ExecResult(full.substring(0, INLINE_OUTPUT_LIMIT) + "\n[输出截断，转盘失败]",
                    exitCode, timedOut, false, null, null);
        }
    }

    private Integer parseExit(String tail) {
        try {
            return Integer.parseInt(tail.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void sleepQuiet() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 一条长活 bash 及其 stdin/stdout 管道。 */
    private static final class Session {
        final Process process;
        final Writer stdin;
        final BufferedReader reader;

        Session(Process process) {
            this.process = process;
            this.stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
            this.reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        }

        boolean readerReady() throws IOException {
            return reader.ready();
        }

        void destroy() {
            try {
                stdin.close();
            } catch (IOException ignored) {
            }
            process.destroyForcibly();
        }
    }
}
