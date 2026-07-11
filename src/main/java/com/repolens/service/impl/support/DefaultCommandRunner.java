package com.repolens.service.impl.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * ProcessBuilder 封装的真实命令执行器。
 * 安全约束：调用方负责传入安全的、白名单映射出的 command 数组——绝不在此处拼接 shell 命令。
 * 超时后强制杀进程（destroyForcibly），输出 stdout+stderr 合并、截取尾部 {@link #OUTPUT_TAIL_CHARS} 字符。
 */
@Slf4j
@Component
public class DefaultCommandRunner implements CommandRunner {

    static final int OUTPUT_TAIL_CHARS = 8000;

    /**
     * 追加到现有 PATH 前面（而不是覆盖），确保 mvn/npm 等工具可被找到。
     * 只追加尚未在 PATH 中出现的路径，避免重复。
     */
    static final String EXTRA_PATH = "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin";

    @Override
    public RunResult run(String[] command, Path workDir, long timeoutMs) {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true); // merge stderr into stdout
        // 关闭 stdin（从 /dev/null 读）：这些命令都不需要标准输入，
        // 否则像 `claude -p` 会等 stdin 3 秒并打印 "no stdin data received" 警告。
        pb.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));

        // 确保常见工具路径在环境变量 PATH 中（mvn/npm 在非标准位置时也可以找到）。
        Map<String, String> env = pb.environment();
        String existing = env.getOrDefault("PATH", "");
        env.put("PATH", EXTRA_PATH + (existing.isEmpty() ? "" : ":" + existing));

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            log.warn("CommandRunner: failed to start process, cmd={}, err={}", command[0], e.getMessage());
            return new RunResult(-1, false, "Failed to start process: " + e.getMessage());
        }

        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    output.append(line).append("\n");
                }
            } catch (IOException e) {
                // process may have been killed; ignore
            }
        });
        reader.setDaemon(true);
        reader.start();

        boolean timedOut = false;
        int exitCode;
        try {
            boolean completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!completed) {
                timedOut = true;
                process.destroyForcibly();
                exitCode = -1;
                log.warn("CommandRunner: command timed out after {}ms, cmd={}", timeoutMs, command[0]);
            } else {
                exitCode = process.exitValue();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new RunResult(-1, false, "Interrupted while waiting for process");
        }

        try {
            reader.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        String full = output.toString();
        String tail = full.length() > OUTPUT_TAIL_CHARS
                ? full.substring(full.length() - OUTPUT_TAIL_CHARS)
                : full;

        return new RunResult(exitCode, timedOut, tail);
    }
}
