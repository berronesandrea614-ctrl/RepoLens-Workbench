package com.repolens.service.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class RipgrepRunner {

    private static final int MAX_MATCHES = 200;
    private static final int MAX_CHARS = 32_000;
    private static final long TIMEOUT_MS = 30_000L;

    private static final java.util.Set<String> BUILD_SKIP_DIRS =
            java.util.Set.of("node_modules", "target", ".git", "dist", ".idea");

    private static final boolean HAS_RG;
    static {
        boolean has = false;
        try {
            Process p = new ProcessBuilder("rg", "--version").start();
            has = p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception ignored) {}
        HAS_RG = has;
    }

    public enum Mode { FILES_WITH_MATCHES, CONTEXT, COUNT }

    public GrepResult grep(String pattern, Path base, String glob,
                           boolean caseInsensitive, Mode mode) {
        if (HAS_RG) {
            return grepWithRg(pattern, base, glob, caseInsensitive, mode);
        }
        return grepWithJava(pattern, base, glob, caseInsensitive, mode);
    }

    private GrepResult grepWithRg(String pattern, Path base, String glob,
                                   boolean caseInsensitive, Mode mode) {
        List<String> cmd = new ArrayList<>();
        cmd.add("rg");
        cmd.add("-F");
        cmd.add("--no-messages");
        cmd.add("-M"); cmd.add("500");
        if (caseInsensitive) cmd.add("-i");
        if (glob != null && !glob.isBlank()) { cmd.add("-g"); cmd.add(glob); }
        switch (mode) {
            case FILES_WITH_MATCHES -> cmd.add("-l");
            case COUNT              -> cmd.add("-c");
            case CONTEXT            -> { cmd.add("-n"); cmd.add("-H"); cmd.add("-C"); cmd.add("2"); }
        }
        cmd.add("--");
        cmd.add(pattern);
        cmd.add(base.toString());

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(base.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                int count = 0;
                while ((line = r.readLine()) != null && count < MAX_MATCHES
                        && sb.length() < MAX_CHARS) {
                    sb.append(line).append('\n');
                    count++;
                }
            }
            boolean finished = p.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished) p.destroyForcibly();
            return new GrepResult(sb.toString(), sb.length() >= MAX_CHARS, !finished);
        } catch (Exception e) {
            log.warn("rg execution failed, falling back to java grep: {}", e.getMessage());
            return grepWithJava(pattern, base, glob, caseInsensitive, mode);
        }
    }

    private GrepResult grepWithJava(String pattern, Path base, String glob,
                                     boolean caseInsensitive, Mode mode) {
        String searchPattern = caseInsensitive ? pattern.toLowerCase() : pattern;
        PathMatcher matcher = glob != null && !glob.isBlank()
                ? FileSystems.getDefault().getPathMatcher("glob:" + glob) : null;
        StringBuilder sb = new StringBuilder();
        int[] count = {0};
        try {
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                    return BUILD_SKIP_DIRS.contains(dir.getFileName().toString())
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes a) throws IOException {
                    if (count[0] >= MAX_MATCHES || sb.length() >= MAX_CHARS)
                        return FileVisitResult.TERMINATE;
                    if (matcher != null && !matcher.matches(file.getFileName()))
                        return FileVisitResult.CONTINUE;
                    List<String> lines = Files.readAllLines(file);
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        String check = caseInsensitive ? line.toLowerCase() : line;
                        if (check.contains(searchPattern)) {
                            String rel = base.relativize(file).toString();
                            if (mode == Mode.FILES_WITH_MATCHES) {
                                sb.append(rel).append('\n');
                                count[0]++;
                                return FileVisitResult.CONTINUE;
                            } else {
                                sb.append(rel).append(':').append(i + 1).append(':').append(line).append('\n');
                                count[0]++;
                            }
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception e) {
            log.warn("java grep walk failed: {}", e.getMessage());
        }
        return new GrepResult(sb.toString(), sb.length() >= MAX_CHARS, false);
    }

    public record GrepResult(String output, boolean truncated, boolean timedOut) {}
}
