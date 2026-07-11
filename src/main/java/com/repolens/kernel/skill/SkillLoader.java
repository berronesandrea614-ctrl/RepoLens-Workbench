package com.repolens.kernel.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * skill 加载器：从三处来源真读 {@code SKILL.md} 并解析成 {@link SkillDefinition}。
 *
 * <ul>
 *   <li><b>内置（builtin）</b>：classpath 下 {@code skills/<name>/SKILL.md}（随内核发布，永远可用）；</li>
 *   <li><b>个人（personal）</b>：{@code ~/.claude/skills/<name>/SKILL.md}；</li>
 *   <li><b>项目（project）</b>：{@code <repoDir>/.claude/skills/<name>/SKILL.md}（随仓库走）。</li>
 * </ul>
 *
 * <p>frontmatter 解析走极简手写解析（只认 {@code name}/{@code description}/{@code disable-model-invocation}
 * 三个扁平单行键），不引第三方 YAML 依赖——与本内核「默认 0 外部依赖」一致。frontmatter 缺失/损坏时
 * 降级：{@code name} 取目录名、{@code description} 取正文首行，绝不因单个坏 skill 崩掉加载。
 */
@Component
public class SkillLoader {

    private static final Logger log = LoggerFactory.getLogger(SkillLoader.class);

    /** 内置 skill 的 classpath 扫描模式（支持 jar 内资源）。 */
    private static final String BUILTIN_PATTERN = "classpath*:skills/*/SKILL.md";

    private static final String FRONT_DELIM = "---";

    private final PathMatchingResourcePatternResolver resourceResolver =
            new PathMatchingResourcePatternResolver();

    /** 扫描内置 classpath skill。失败返回空列表（不阻断）。 */
    public List<SkillDefinition> loadBuiltin() {
        List<SkillDefinition> out = new ArrayList<>();
        try {
            Resource[] resources = resourceResolver.getResources(BUILTIN_PATTERN);
            for (Resource r : resources) {
                try (InputStream in = r.getInputStream()) {
                    String raw = readAll(in);
                    String dirName = builtinDirName(r);
                    SkillDefinition d = parse(raw, dirName, "builtin", null);
                    if (d != null) {
                        out.add(d);
                    }
                } catch (Exception e) {
                    log.warn("[skill] 读取内置 skill 资源失败 {}: {}", safeName(r), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[skill] 扫描内置 skill 失败: {}", e.getMessage());
        }
        return out;
    }

    /** 扫描一个文件系统目录下的 {@code <name>/SKILL.md}（personal / project）。目录不存在返回空。 */
    public List<SkillDefinition> loadFromDir(Path skillsDir, String source) {
        List<SkillDefinition> out = new ArrayList<>();
        if (skillsDir == null || !Files.isDirectory(skillsDir)) {
            return out;
        }
        try (var sub = Files.list(skillsDir)) {
            List<Path> dirs = sub.filter(Files::isDirectory).sorted().toList();
            for (Path dir : dirs) {
                Path skillMd = dir.resolve("SKILL.md");
                if (!Files.isRegularFile(skillMd)) {
                    continue;
                }
                try {
                    String raw = Files.readString(skillMd, StandardCharsets.UTF_8);
                    SkillDefinition d = parse(raw, dir.getFileName().toString(), source, dir.toString());
                    if (d != null) {
                        out.add(d);
                    }
                } catch (Exception e) {
                    log.warn("[skill] 读取 {} skill 失败 {}: {}", source, skillMd, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[skill] 扫描 {} skill 目录 {} 失败: {}", source, skillsDir, e.getMessage());
        }
        return out;
    }

    /**
     * 解析一份 SKILL.md 文本为 {@link SkillDefinition}。
     * frontmatter 缺失时降级：name 取目录名、description 取正文首个非空行。
     * name 非法/正文为空则返回 null（跳过）。
     */
    SkillDefinition parse(String raw, String dirName, String source, String dir) {
        if (raw == null) {
            return null;
        }
        String text = raw.replace("\r\n", "\n").replace("\r", "\n");
        String frontmatter = null;
        String body;
        String trimmedLead = stripLeadingBlank(text);
        if (trimmedLead.startsWith(FRONT_DELIM + "\n") || trimmedLead.equals(FRONT_DELIM)) {
            int start = trimmedLead.indexOf('\n');
            int end = trimmedLead.indexOf("\n" + FRONT_DELIM, start);
            if (start >= 0 && end > start) {
                frontmatter = trimmedLead.substring(start + 1, end);
                int bodyStart = trimmedLead.indexOf('\n', end + 1);
                body = bodyStart < 0 ? "" : trimmedLead.substring(bodyStart + 1);
            } else {
                body = text;
            }
        } else {
            body = text;
        }

        String name = firstNonBlank(frontValue(frontmatter, "name"), dirName);
        if (name == null || !isSafeName(name)) {
            log.warn("[skill] 跳过非法 skill 名 name='{}' dir='{}'", name, dirName);
            return null;
        }
        String description = firstNonBlank(frontValue(frontmatter, "description"), firstBodyLine(body));
        if (description == null) {
            description = name;
        }
        boolean disableModel = "true".equalsIgnoreCase(frontValue(frontmatter, "disable-model-invocation"));
        String finalBody = body == null ? "" : body.strip();
        if (finalBody.isEmpty()) {
            log.warn("[skill] 跳过空正文 skill '{}'", name);
            return null;
        }
        return new SkillDefinition(name, description, finalBody, disableModel, source, dir);
    }

    /** 取 frontmatter 里某个扁平单行键的值（去引号），无则 null。 */
    private static String frontValue(String frontmatter, String key) {
        if (frontmatter == null) {
            return null;
        }
        for (String line : frontmatter.split("\n")) {
            String l = line.strip();
            if (l.isEmpty() || l.startsWith("#")) {
                continue;
            }
            int colon = l.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String k = l.substring(0, colon).strip();
            if (k.equalsIgnoreCase(key)) {
                String v = l.substring(colon + 1).strip();
                return stripQuotes(v);
            }
        }
        return null;
    }

    private static String firstBodyLine(String body) {
        if (body == null) {
            return null;
        }
        for (String line : body.split("\n")) {
            String l = line.strip();
            // 跳过 markdown 标题符号，取有内容的一行
            String cleaned = l.replaceAll("^#+\\s*", "").strip();
            if (!cleaned.isEmpty()) {
                return cleaned.length() > 300 ? cleaned.substring(0, 300) : cleaned;
            }
        }
        return null;
    }

    private static String stripLeadingBlank(String s) {
        int i = 0;
        while (i < s.length() && (s.charAt(i) == '\n' || s.charAt(i) == ' ' || s.charAt(i) == '\t')) {
            i++;
        }
        return s.substring(i);
    }

    private static String stripQuotes(String v) {
        if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.strip();
        }
        return b == null || b.isBlank() ? null : b.strip();
    }

    /** skill 名只允许小写字母/数字/连字符（对齐开放标准），防路径穿越 + 保持斜杠可寻址。 */
    static boolean isSafeName(String name) {
        if (name == null || name.isEmpty() || name.startsWith("-") || name.endsWith("-")) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** 从 classpath 资源 URL 里取其父目录名作为 skill 名兜底（frontmatter 缺 name 时用）。 */
    private static String builtinDirName(Resource r) {
        try {
            String url = r.getURL().toString();
            // 形如 .../skills/<name>/SKILL.md
            String noFile = url.substring(0, url.lastIndexOf('/'));
            return noFile.substring(noFile.lastIndexOf('/') + 1);
        } catch (Exception e) {
            return "unknown-skill";
        }
    }

    private static String safeName(Resource r) {
        try {
            return r.getURL().toString();
        } catch (Exception e) {
            return String.valueOf(r);
        }
    }

    private static String readAll(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = br.read(buf)) >= 0) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }
}
