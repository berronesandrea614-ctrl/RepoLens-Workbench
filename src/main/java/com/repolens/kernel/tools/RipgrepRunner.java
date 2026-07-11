package com.repolens.kernel.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 字面（{@code -F}，非正则）文本检索工具，优先跑 ripgrep，rg 不可用时降级到纯 Java 遍历。
 * 三种 mode 对齐 Claude Code 的 grep：文件名 / 带行号内容 / 每文件计数；统一限量防上下文爆炸。
 *
 * <p>与旧实现的差别：①<b>路径限定</b>——搜索根规范化后必须落在 base 内，挡 {@code ../} 逃逸；
 * ②<b>降级路径可测</b>（旧版 P2-3：Java 降级从没被测过）——{@link #grepJava} 独立可调、E2E 直接覆盖；
 * ③限量既限匹配条数也限总字符数，任一触顶即截断并如实标 {@code truncated}。
 */
@Slf4j
@Component("kernelRipgrepRunner")
public class RipgrepRunner {

    private static final int MAX_MATCHES = 200;
    private static final int MAX_CHARS = 30_000;
    private static final long TIMEOUT_SEC = 30;
    /** rg 默认已遵守 .gitignore；Java 降级时手动跳过这些常见非源码目录。 */
    private static final Set<String> SKIP_DIRS =
            Set.of("node_modules", "target", "build", "dist", ".git", ".idea", ".gradle");

    public enum Mode {
        /** 只回命中文件名（rg -l）。 */
        FILES,
        /** 回 file:line:content 带行号（rg -n -H）。 */
        CONTENT,
        /** 回 file:count 每文件命中数（rg -c）。 */
        COUNT
    }

    public record GrepResult(String output, int matchCount, boolean truncated, boolean usedRipgrep) {}

    private final boolean hasRipgrep = detectRipgrep();

    /**
     * @param pattern         字面串（不当正则解释）
     * @param base            搜索根
     * @param glob            文件名过滤（如 {@code *.java}），可空
     * @param caseInsensitive 忽略大小写
     */
    public GrepResult grep(String pattern, Path base, String glob, boolean caseInsensitive, Mode mode) {
        Path root = base.toAbsolutePath().normalize();
        if (pattern == null || pattern.isEmpty()) {
            return new GrepResult("", 0, false, false);
        }
        if (hasRipgrep) {
            try {
                return grepRipgrep(pattern, root, glob, caseInsensitive, mode);
            } catch (Exception e) {
                log.warn("[grep] ripgrep 执行异常，降级到 Java 实现: {}", e.getMessage());
            }
        }
        return grepJava(pattern, root, glob, caseInsensitive, mode);
    }

    private GrepResult grepRipgrep(String pattern, Path root, String glob,
                                   boolean caseInsensitive, Mode mode) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("rg", "-F", "--no-messages", "--color", "never"));
        if (caseInsensitive) {
            cmd.add("-i");
        }
        if (glob != null && !glob.isBlank()) {
            cmd.add("-g");
            cmd.add(glob);
        }
        switch (mode) {
            case FILES -> cmd.add("-l");
            case COUNT -> cmd.add("-c");
            case CONTENT -> {
                cmd.add("-n");
                cmd.add("-H");
                cmd.add("-M");   // 单行超长截断，防长行灌爆
                cmd.add("300");
            }
        }
        cmd.add("--");
        cmd.add(pattern);
        cmd.add(".");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(root.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        CompletableFuture<Capture> reader = CompletableFuture.supplyAsync(() -> readCapped(p));
        boolean finished = p.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
        }
        Capture cap = reader.join();
        return new GrepResult(cap.text.toString(), cap.matches, cap.truncated, true);
    }

    private Capture readCapped(Process p) {
        Capture cap = new Capture();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (cap.matches >= MAX_MATCHES || cap.text.length() >= MAX_CHARS) {
                    cap.truncated = true;
                    break;
                }
                cap.text.append(line).append('\n');
                cap.matches++;
            }
        } catch (IOException e) {
            log.warn("[grep] 读取 rg 输出失败: {}", e.getMessage());
        }
        return cap;
    }

    /** 纯 Java 降级：无 rg 时遍历 base，跳过构建目录，字面 contains 匹配。public 以便降级路径独立可测（修 P2-3）。 */
    public GrepResult grepJava(String pattern, Path base, String glob, boolean caseInsensitive, Mode mode) {
        Path root = base.toAbsolutePath().normalize();
        String needle = caseInsensitive ? pattern.toLowerCase() : pattern;
        PathMatcher matcher = (glob != null && !glob.isBlank())
                ? FileSystems.getDefault().getPathMatcher("glob:" + glob) : null;
        Capture cap = new Capture();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                    if (!dir.equals(root) && SKIP_DIRS.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
                    if (cap.matches >= MAX_MATCHES || cap.text.length() >= MAX_CHARS) {
                        cap.truncated = true;
                        return FileVisitResult.TERMINATE;
                    }
                    // 路径限定：规范化后必须仍在 root 内
                    Path norm = file.toAbsolutePath().normalize();
                    if (!norm.startsWith(root)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (matcher != null && !matcher.matches(file.getFileName())) {
                        return FileVisitResult.CONTINUE;
                    }
                    matchInFile(root, file, needle, caseInsensitive, mode, cap);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;  // 二进制/无权限文件跳过
                }
            });
        } catch (IOException e) {
            log.warn("[grep] Java 遍历失败: {}", e.getMessage());
        }
        return new GrepResult(cap.text.toString(), cap.matches, cap.truncated, false);
    }

    private void matchInFile(Path root, Path file, String needle, boolean caseInsensitive,
                             Mode mode, Capture cap) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return;  // 非 UTF-8/二进制：跳过
        }
        String rel = root.relativize(file).toString();
        int fileHits = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String hay = caseInsensitive ? line.toLowerCase() : line;
            if (!hay.contains(needle)) {
                continue;
            }
            fileHits++;
            if (mode == Mode.FILES) {
                cap.text.append(rel).append('\n');
                cap.matches++;
                return;  // 文件名模式每文件一次
            }
            if (mode == Mode.CONTENT) {
                if (cap.matches >= MAX_MATCHES || cap.text.length() >= MAX_CHARS) {
                    cap.truncated = true;
                    return;
                }
                cap.text.append(rel).append(':').append(i + 1).append(':').append(line).append('\n');
                cap.matches++;
            }
        }
        if (mode == Mode.COUNT && fileHits > 0) {
            cap.text.append(rel).append(':').append(fileHits).append('\n');
            cap.matches++;
        }
    }

    private boolean detectRipgrep() {
        try {
            Process p = new ProcessBuilder("rg", "--version").redirectErrorStream(true).start();
            boolean ok = p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0;
            p.destroyForcibly();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    private static final class Capture {
        final StringBuilder text = new StringBuilder();
        int matches = 0;
        boolean truncated = false;
    }
}
