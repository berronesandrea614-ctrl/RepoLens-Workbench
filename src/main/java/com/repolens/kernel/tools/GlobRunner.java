package com.repolens.kernel.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 文件名 glob 匹配工具（对齐 Claude Code 的 Glob）：支持 {@code **} 跨目录、按<b>修改时间倒序</b>返回
 * （最近改的在前，符合"找我刚动过的文件"直觉），上限 100 条。跳过构建产物目录，结果限定在 base 内。
 *
 * <p>glob 语法走 JVM {@code FileSystems.getPathMatcher("glob:…")}：{@code *} 不跨 {@code /}，
 * {@code **} 跨目录。传入的 pattern 相对 base 匹配（对每个文件用其相对路径匹配）。
 */
@Slf4j
@Component
public class GlobRunner {

    private static final int MAX_RESULTS = 100;
    private static final Set<String> SKIP_DIRS =
            Set.of("node_modules", "target", "build", "dist", ".git", ".idea", ".gradle");

    public record GlobResult(List<String> paths, boolean truncated) {}

    /**
     * @param pattern glob 模式，相对 base，如 {@code **}{@code /*.java}、{@code src/**}{@code /*Test.java}
     * @param base    搜索根
     */
    public GlobResult glob(String pattern, Path base) {
        Path root = base.toAbsolutePath().normalize();
        if (pattern == null || pattern.isBlank()) {
            return new GlobResult(List.of(), false);
        }
        // JVM glob 的 **/ 要求真有分隔符，导致 **/*.java 匹配不到根级文件。
        // 补一个"塌缩"变体（去掉所有 **/），让 **/ 语义上能匹配零级目录——对齐真实工具直觉。
        PathMatcher primary = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        String collapsed = pattern.replace("**/", "");
        PathMatcher secondary = collapsed.equals(pattern)
                ? null : FileSystems.getDefault().getPathMatcher("glob:" + collapsed);
        final List<Hit> hits = new ArrayList<>();
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
                    Path norm = file.toAbsolutePath().normalize();
                    if (!norm.startsWith(root)) {
                        return FileVisitResult.CONTINUE;  // 路径限定
                    }
                    Path rel = root.relativize(norm);
                    if (primary.matches(rel) || (secondary != null && secondary.matches(rel))) {
                        hits.add(new Hit(rel.toString(), a.lastModifiedTime()));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("[glob] 遍历失败: {}", e.getMessage());
        }

        hits.sort(Comparator.comparing((Hit h) -> h.mtime).reversed());  // mtime 倒序
        boolean truncated = hits.size() > MAX_RESULTS;
        List<Hit> capped = truncated ? hits.subList(0, MAX_RESULTS) : hits;
        List<String> paths = new ArrayList<>(capped.size());
        for (Hit h : capped) {
            paths.add(h.rel);
        }
        return new GlobResult(paths, truncated);
    }

    private record Hit(String rel, FileTime mtime) {}
}
