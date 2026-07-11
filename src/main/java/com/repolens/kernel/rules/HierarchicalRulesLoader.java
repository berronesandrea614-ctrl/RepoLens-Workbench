package com.repolens.kernel.rules;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 记忆层级加载器（M6，蓝图记忆层级）。把仓库里分层的 {@code CLAUDE.md}/{@code AGENTS.md} 规则
 * 与 {@code .claude/rules/*} 作用域规则，拼接成一个注入系统提示词的<b>规则块</b>。
 *
 * <h3>遍历语义</h3>
 * <ul>
 *   <li><b>向上（root → repoDir）</b>：从 repoDir 一路到文件系统根，逐级收集 {@code CLAUDE.md}/{@code AGENTS.md}，
 *       <b>越靠上越先</b>（顶层通用规则在前、越近越具体在后，后者可覆盖/细化前者）；</li>
 *   <li><b>向下</b>：递归 repoDir 子目录里的 {@code CLAUDE.md}/{@code AGENTS.md}（子模块级规则），按路径排序附在后。</li>
 * </ul>
 *
 * <h3>{@code @import}</h3>
 * 规则文件里出现 {@code @import <path>}（行首）时，把该路径（相对所在文件目录、或绝对）的文件内容
 * <b>就地展开</b>替换该行。支持有限深度递归展开、并做环检测（已展开过的文件不再展开）。
 *
 * <h3>{@code .claude/rules/*} 的 paths 作用域</h3>
 * {@code <repoDir>/.claude/rules/} 下的每个 {@code *.md} 可带 front-matter：
 * <pre>
 * ---
 * paths: ["src/main/java/**", "*.sql"]
 * ---
 * 规则正文…
 * </pre>
 * 该规则<b>只在当前 run 触及的文件/目录匹配某个 glob 时</b>才注入（作用域规则）。
 * 无 {@code paths} 的视为全局。glob 支持 {@code **}（跨目录）、{@code *}（单段内任意）、{@code ?}。
 */
@Component("kernelHierarchicalRulesLoader")
public class HierarchicalRulesLoader {

    private static final Logger log = LoggerFactory.getLogger(HierarchicalRulesLoader.class);

    private static final String[] RULE_FILE_NAMES = {"CLAUDE.md", "AGENTS.md"};
    private static final int MAX_IMPORT_DEPTH = 5;
    private static final int MAX_DOWNWARD_DEPTH = 6;

    private static final Pattern IMPORT_LINE =
            Pattern.compile("(?m)^[ \\t]*@import[ \\t]+(.+?)[ \\t]*$");
    private static final Pattern FRONT_MATTER =
            Pattern.compile("(?s)\\A---\\s*\\n(.*?)\\n---\\s*\\n?(.*)\\z");
    private static final Pattern PATHS_LINE =
            Pattern.compile("(?m)^\\s*paths\\s*:\\s*(.+?)\\s*$");

    /**
     * 加载并拼接规则块。
     *
     * @param repoDir       仓库根目录（作用域/向上遍历锚点）
     * @param scopeContexts 本 run 关注的文件/目录相对路径（用于 paths 作用域匹配）；可空/空
     * @return 规则块文本（可直接拼进 system prompt）；无任何规则则返回空串
     */
    public String load(Path repoDir, List<String> scopeContexts) {
        if (repoDir == null) {
            return "";
        }
        List<Section> sections = new ArrayList<>();

        // 1) 向上：root → repoDir，逐级 CLAUDE.md/AGENTS.md
        List<Path> upwardDirs = new ArrayList<>();
        for (Path d = repoDir.toAbsolutePath().normalize(); d != null; d = d.getParent()) {
            upwardDirs.add(d);
        }
        java.util.Collections.reverse(upwardDirs); // root 在前
        for (Path dir : upwardDirs) {
            for (String fn : RULE_FILE_NAMES) {
                Path f = dir.resolve(fn);
                if (Files.isRegularFile(f)) {
                    String body = expandImports(f, new LinkedHashSet<>(), 0);
                    sections.add(new Section(relLabel(repoDir, f), body));
                }
            }
        }

        // 2) 向下：repoDir 子目录里的 CLAUDE.md/AGENTS.md（排除已在向上阶段取过的 repoDir 自身）
        sections.addAll(downwardSections(repoDir));

        // 3) .claude/rules/*.md 作用域规则
        sections.addAll(scopedRuleSections(repoDir, scopeContexts));

        if (sections.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# 项目规则（记忆层级，按作用域拼接）\n");
        for (Section s : sections) {
            sb.append("\n<rules source=\"").append(s.label).append("\">\n");
            sb.append(s.body.strip()).append('\n');
            sb.append("</rules>\n");
        }
        return sb.toString();
    }

    // -------------------------------------------------------------- downward

    private List<Section> downwardSections(Path repoDir) {
        List<Section> out = new ArrayList<>();
        Path base = repoDir.toAbsolutePath().normalize();
        try (Stream<Path> walk = Files.walk(base, MAX_DOWNWARD_DEPTH)) {
            List<Path> found = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.equals("CLAUDE.md") || n.equals("AGENTS.md");
                    })
                    // 排除 repoDir 根下的（向上阶段已收），只要子目录里的
                    .filter(p -> !p.getParent().equals(base))
                    // 排除 .claude/rules 目录下的（由作用域阶段处理）
                    .filter(p -> !p.toString().contains(".claude" + java.io.File.separator + "rules"))
                    .sorted()
                    .toList();
            for (Path f : found) {
                String body = expandImports(f, new LinkedHashSet<>(), 0);
                out.add(new Section(relLabel(repoDir, f), body));
            }
        } catch (IOException e) {
            log.warn("[rules] 向下遍历失败 {}: {}", repoDir, e.getMessage());
        }
        return out;
    }

    // -------------------------------------------------------------- scoped rules

    private List<Section> scopedRuleSections(Path repoDir, List<String> scopeContexts) {
        List<Section> out = new ArrayList<>();
        Path rulesDir = repoDir.resolve(".claude").resolve("rules");
        if (!Files.isDirectory(rulesDir)) {
            return out;
        }
        List<Path> ruleFiles;
        try (Stream<Path> s = Files.list(rulesDir)) {
            ruleFiles = s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("[rules] 读取 .claude/rules 失败: {}", e.getMessage());
            return out;
        }
        for (Path f : ruleFiles) {
            String raw;
            try {
                raw = Files.readString(f, StandardCharsets.UTF_8);
            } catch (IOException e) {
                continue;
            }
            List<String> paths = new ArrayList<>();
            String body = stripFrontMatterAndCollectPaths(raw, paths);
            // 先展开 body 里的 @import
            body = expandImportsText(body, f.getParent(), new LinkedHashSet<>(), 0);
            if (paths.isEmpty()) {
                // 无 paths → 全局作用域规则
                out.add(new Section(relLabel(repoDir, f) + " (全局)", body));
            } else if (matchesAnyScope(paths, scopeContexts)) {
                out.add(new Section(relLabel(repoDir, f) + " (paths=" + String.join(",", paths) + ")", body));
            } else {
                log.debug("[rules] 作用域规则 {} 未命中当前上下文，跳过", f);
            }
        }
        return out;
    }

    /** 剥离 front-matter，把其中的 paths 收集出来，返回正文。 */
    private String stripFrontMatterAndCollectPaths(String raw, List<String> paths) {
        Matcher fm = FRONT_MATTER.matcher(raw);
        if (!fm.find()) {
            return raw;
        }
        String header = fm.group(1);
        String body = fm.group(2);
        Matcher pm = PATHS_LINE.matcher(header);
        if (pm.find()) {
            paths.addAll(parseGlobList(pm.group(1)));
        }
        return body;
    }

    /** 解析 {@code ["a/**", "*.sql"]} 或 {@code a/**, *.sql} 成 glob 列表。 */
    private List<String> parseGlobList(String s) {
        List<String> out = new ArrayList<>();
        String t = s.trim();
        if (t.startsWith("[")) {
            t = t.substring(1);
        }
        if (t.endsWith("]")) {
            t = t.substring(0, t.length() - 1);
        }
        for (String part : t.split(",")) {
            String g = part.trim().replaceAll("^[\"']|[\"']$", "").trim();
            if (!g.isEmpty()) {
                out.add(g);
            }
        }
        return out;
    }

    private boolean matchesAnyScope(List<String> globs, List<String> scopeContexts) {
        if (scopeContexts == null || scopeContexts.isEmpty()) {
            return false;
        }
        for (String glob : globs) {
            Pattern re = globToRegex(glob);
            for (String ctx : scopeContexts) {
                String norm = ctx.replace('\\', '/');
                if (re.matcher(norm).matches()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** glob → 正则：{@code **}=跨目录任意（含/），{@code *}=单段内任意（不含/），{@code ?}=单字符。 */
    static Pattern globToRegex(String glob) {
        StringBuilder re = new StringBuilder();
        int i = 0;
        int n = glob.length();
        while (i < n) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < n && glob.charAt(i + 1) == '*') {
                    re.append(".*");
                    i += 2;
                    // 吸收 **/ 的斜杠，使 a/**/b 也能匹配 a/b
                    if (i < n && glob.charAt(i) == '/') {
                        i++;
                    }
                    continue;
                } else {
                    re.append("[^/]*");
                }
            } else if (c == '?') {
                re.append("[^/]");
            } else if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
                re.append('\\').append(c);
            } else {
                re.append(c);
            }
            i++;
        }
        return Pattern.compile(re.toString());
    }

    // -------------------------------------------------------------- @import

    private String expandImports(Path file, Set<Path> seen, int depth) {
        Path norm = file.toAbsolutePath().normalize();
        if (depth > MAX_IMPORT_DEPTH || !seen.add(norm)) {
            return "";
        }
        String raw;
        try {
            raw = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
        return expandImportsText(raw, file.getParent(), seen, depth);
    }

    /** 对一段文本展开其中的 {@code @import}，import 目标相对 {@code baseDir} 解析。 */
    private String expandImportsText(String text, Path baseDir, Set<Path> seen, int depth) {
        Matcher m = IMPORT_LINE.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String target = m.group(1).trim();
            Path imp = resolveImport(baseDir, target);
            String replacement;
            if (imp != null && Files.isRegularFile(imp) && depth < MAX_IMPORT_DEPTH) {
                replacement = "<!-- @import " + target + " -->\n" + expandImports(imp, seen, depth + 1);
            } else {
                replacement = "<!-- @import 未解析: " + target + " -->";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private Path resolveImport(Path baseDir, String target) {
        try {
            Path p = Path.of(target);
            if (p.isAbsolute()) {
                return p.normalize();
            }
            return baseDir == null ? p.normalize() : baseDir.resolve(p).normalize();
        } catch (Exception e) {
            return null;
        }
    }

    private String relLabel(Path repoDir, Path f) {
        try {
            Path base = repoDir.toAbsolutePath().normalize();
            Path fa = f.toAbsolutePath().normalize();
            if (fa.startsWith(base)) {
                return base.relativize(fa).toString().replace('\\', '/');
            }
            return fa.toString().replace('\\', '/');
        } catch (Exception e) {
            return f.toString();
        }
    }

    private record Section(String label, String body) {
    }
}
