package com.repolens.service.support.rules;

import com.repolens.service.support.RepoWorkspaceResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class HierarchicalRulesLoader {

    private static final int MAX_CHARS = 8000;
    private static final int MAX_IMPORT_HOPS = 4;
    private static final List<String> RULE_FILE_NAMES = List.of("CLAUDE.md", "AGENTS.md", ".repolens/rules.md");

    private final RepoWorkspaceResolver repoWorkspaceResolver;

    public String load(Path repoRoot, Path startDir) {
        try {
            List<String> ruleSections = new ArrayList<>();
            List<Path> dirs = collectDirs(repoRoot, startDir);
            for (Path dir : dirs) {
                for (String name : RULE_FILE_NAMES) {
                    Path ruleFile = dir.resolve(name);
                    if (Files.exists(ruleFile) && Files.isRegularFile(ruleFile)) {
                        try {
                            repoWorkspaceResolver.resolveSafeFilePath(repoRoot,
                                    repoRoot.relativize(ruleFile).toString());
                            String content = Files.readString(ruleFile);
                            content = resolveImports(content, ruleFile.getParent(), repoRoot, 0, new HashSet<>());
                            ruleSections.add("# 规则来自 " + repoRoot.relativize(ruleFile) + "\n" + content);
                        } catch (Exception e) {
                            log.warn("HierarchicalRulesLoader: skip {} ({})", ruleFile, e.getMessage());
                        }
                    }
                }
            }
            StringBuilder sb = new StringBuilder();
            for (String section : ruleSections) {
                if (sb.length() + section.length() > MAX_CHARS) break;
                sb.append(section).append("\n---\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("HierarchicalRulesLoader.load failed (fail-safe): {}", e.getMessage());
            return "";
        }
    }

    private List<Path> collectDirs(Path repoRoot, Path current) {
        List<Path> result = new ArrayList<>();
        Path p = current;
        while (p != null && p.startsWith(repoRoot)) {
            result.add(0, p);
            if (p.equals(repoRoot)) break;
            p = p.getParent();
        }
        return result;
    }

    private String resolveImports(String content, Path dir, Path repoRoot, int hop, Set<String> seen) {
        if (hop >= MAX_IMPORT_HOPS) return content;
        StringBuilder sb = new StringBuilder();
        for (String line : content.split("\n")) {
            if (line.startsWith("@import ")) {
                String ref = line.substring(8).trim();
                Path imported = dir.resolve(ref).normalize();
                String key = imported.toString();
                if (!seen.contains(key) && imported.startsWith(repoRoot) && Files.exists(imported)) {
                    seen.add(key);
                    try {
                        String sub = Files.readString(imported);
                        sb.append(resolveImports(sub, imported.getParent(), repoRoot, hop + 1, seen));
                    } catch (Exception e) {
                        sb.append(line).append('\n');
                    }
                }
            } else {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
}
