package com.repolens.service.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class PersistentShell {

    private final Map<String, Process> processes = new ConcurrentHashMap<>();
    private final Map<String, StringBuilder> outputs = new ConcurrentHashMap<>();
    private final CommandSafetyChecker safetyChecker;

    public PersistentShell(CommandSafetyChecker safetyChecker) {
        this.safetyChecker = safetyChecker;
    }

    public record ShellResult(String shellId, String output, int exitCode) {}

    public ShellResult exec(String command, String cwd) {
        String blockReason = safetyChecker.check(command);
        if (blockReason != null) {
            return new ShellResult(null, "blocked: " + blockReason, -1);
        }
        String id = UUID.randomUUID().toString().substring(0, 8);
        try {
            ProcessBuilder pb = new ProcessBuilder();
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                pb.command("cmd.exe", "/c", command);
            } else {
                pb.command("bash", "-c", command);
            }
            if (cwd != null && !cwd.isBlank()) {
                pb.directory(new java.io.File(cwd));
            }
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            StringBuilder out = new StringBuilder();
            outputs.put(id, out);
            new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        out.append(line).append('\n');
                    }
                } catch (Exception ignored) { }
            }, "shell-" + id).start();
            boolean finished = proc.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return new ShellResult(id, out + "\n[超时，已强制终止]", -1);
            }
            return new ShellResult(id, out.toString(), proc.exitValue());
        } catch (Exception e) {
            return new ShellResult(null, "exec error: " + e.getMessage(), -1);
        }
    }

    public String getOutput(String shellId) {
        StringBuilder out = outputs.get(shellId);
        return out != null ? out.toString() : "(shell not found)";
    }

    public boolean kill(String shellId) {
        Process proc = processes.get(shellId);
        if (proc != null && proc.isAlive()) {
            proc.destroyForcibly();
            return true;
        }
        return false;
    }
}
