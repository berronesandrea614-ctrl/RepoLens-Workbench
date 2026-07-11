package com.repolens.hooks.impl;

import com.repolens.hooks.BuiltinHook;
import com.repolens.hooks.HookContext;
import com.repolens.hooks.HookResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * PostToolUse hook：写操作完成后跑 lint/checkstyle，失败时把错误追加到 toolOutput。
 * 默认关闭（lint-enabled=false），开启后把"靠模型记得"变成确定性代码。
 */
@Slf4j
@Component
public class LintGateHook implements BuiltinHook {

    @Value("${repolens.hooks.lint-enabled:false}")
    private boolean lintEnabled;

    private static final Set<String> WRITE_TOOLS = Set.of(
            "writeFileContent", "editFileContent", "createFileContent", "multiEditFile");

    private static final int LINT_TIMEOUT_SECONDS = 30;

    @Override
    public String name() { return "LintGateHook"; }

    @Override
    public Set<String> lifecycles() { return Set.of("PostToolUse"); }

    @Override
    public boolean matches(String toolName) { return WRITE_TOOLS.contains(toolName); }

    @Override
    public HookResult execute(HookContext ctx) {
        if (!lintEnabled) return HookResult.allow();

        String filePath = (String) ctx.getToolArgs().getOrDefault("filePath", "");
        if (filePath == null || filePath.isBlank()) return HookResult.allow();

        try {
            String ext = filePath.contains(".") ? filePath.substring(filePath.lastIndexOf('.')) : "";
            String result = switch (ext) {
                case ".java" -> runCheckstyle(filePath);
                case ".ts", ".tsx", ".js", ".jsx" -> runEslint(filePath);
                default -> null;
            };
            if (result != null && !result.isBlank()) {
                return HookResult.builder()
                        .continueFlow(true)
                        .decision("continue")
                        .updatedToolOutput("[LintGate] " + result)
                        .build();
            }
        } catch (Exception e) {
            log.warn("LintGateHook failed (fail-safe allow): {}", e.getMessage());
        }
        return HookResult.allow();
    }

    private String runCheckstyle(String filePath) {
        try {
            Path repoRoot = Path.of(".").toAbsolutePath().normalize();
            ProcessBuilder pb = new ProcessBuilder(
                    "mvn", "checkstyle:check", "-Dcheckstyle.includes=" + filePath,
                    "-Dcheckstyle.failOnViolation=false")
                    .directory(repoRoot.toFile())
                    .redirectErrorStream(true);
            Process proc = pb.start();
            boolean finished = proc.waitFor(LINT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) { proc.destroyForcibly(); return null; }
            try (var reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("WARN") || line.contains("ERROR")) sb.append(line).append('\n');
                }
                return sb.isEmpty() ? null : "checkstyle issues:\n" + sb;
            }
        } catch (Exception e) {
            log.debug("LintGateHook: checkstyle not available: {}", e.getMessage());
            return null;
        }
    }

    private String runEslint(String filePath) {
        try {
            Path repoRoot = Path.of(".").toAbsolutePath().normalize();
            ProcessBuilder pb = new ProcessBuilder(
                    "npx", "eslint", filePath, "--format=compact")
                    .directory(repoRoot.toFile())
                    .redirectErrorStream(true);
            Process proc = pb.start();
            boolean finished = proc.waitFor(LINT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) { proc.destroyForcibly(); return null; }
            try (var reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) { sb.append(line).append('\n'); }
                return sb.isEmpty() ? null : "eslint issues:\n" + sb;
            }
        } catch (Exception e) {
            log.debug("LintGateHook: eslint not available: {}", e.getMessage());
            return null;
        }
    }
}
