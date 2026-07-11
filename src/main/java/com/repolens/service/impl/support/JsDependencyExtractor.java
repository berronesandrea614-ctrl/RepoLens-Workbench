package com.repolens.service.impl.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JS / TS 依赖提取器。
 * <ul>
 *   <li><b>清单级（MANIFEST）</b>：package.json 中 dependencies / devDependencies / peerDependencies 新增条目。</li>
 *   <li><b>import 级（IMPORT）</b>：.js/.ts/.jsx/.tsx 源码里新增的 bare specifier import/require，
 *       且未出现在 newContent 的 package.json deps 中（跨文件去重由调用方在 session 级做）。</li>
 * </ul>
 * 过滤：相对路径 (./ ../)、node: 前缀、Node.js 内置模块列表。
 */
@Slf4j
@Component
public class JsDependencyExtractor implements DependencyExtractor {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Node.js 内置模块清单（截至 Node 20）。 */
    static final Set<String> NODE_BUILTINS = Set.of(
            "assert", "async_hooks", "buffer", "child_process", "cluster", "console",
            "constants", "crypto", "dgram", "diagnostics_channel", "dns", "domain",
            "events", "fs", "http", "http2", "https", "inspector", "module", "net",
            "os", "path", "perf_hooks", "process", "punycode", "querystring", "readline",
            "repl", "stream", "string_decoder", "sys", "timers", "tls", "trace_events",
            "tty", "url", "util", "v8", "vm", "wasi", "worker_threads", "zlib"
    );

    /** package.json 中包含依赖的字段名。 */
    private static final Set<String> DEP_FIELDS = Set.of(
            "dependencies", "devDependencies", "peerDependencies", "optionalDependencies"
    );

    /**
     * 匹配 ES import / dynamic import / export from / CommonJS require 的 bare specifier。
     * Group 1 = 引号内容（单引号或双引号）。
     */
    private static final Pattern IMPORT_PATTERN = Pattern.compile(
            "(?:import\\s+(?:[\\w\\s{},*]+\\s+from\\s+|)|export\\s+[\\w\\s{},*]+\\s+from\\s+|" +
            "require\\s*\\(\\s*)" +
            "[\"']([^\"']+)[\"']"
    );

    @Override
    public boolean supports(String filePath) {
        if (filePath == null) return false;
        String lower = filePath.toLowerCase(Locale.ROOT);
        return lower.endsWith("package.json")
                || lower.endsWith(".js")
                || lower.endsWith(".ts")
                || lower.endsWith(".jsx")
                || lower.endsWith(".tsx")
                || lower.endsWith(".mjs")
                || lower.endsWith(".cjs");
    }

    @Override
    public List<ExtractedDep> extractAdded(String filePath, String oldContent, String newContent) {
        if (filePath == null || newContent == null) return List.of();
        String lower = filePath.toLowerCase(Locale.ROOT);

        if (lower.endsWith("package.json")) {
            return extractPackageJsonAdded(filePath, oldContent, newContent);
        } else {
            return extractSourceAdded(filePath, oldContent, newContent);
        }
    }

    // ───────────────────────────── package.json ─────────────────────────────

    private List<ExtractedDep> extractPackageJsonAdded(String filePath, String oldContent, String newContent) {
        Set<String> oldDeps = parsePackageJsonDeps(oldContent);
        Map<String, String> newDepsMap = parsePackageJsonDepsWithVersion(newContent);

        List<ExtractedDep> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : newDepsMap.entrySet()) {
            String pkg = entry.getKey();
            if (!oldDeps.contains(pkg)) {
                result.add(new ExtractedDep(
                        ExtractedDep.ECOSYSTEM_NPM,
                        pkg,
                        entry.getValue(),
                        ExtractedDep.SOURCE_MANIFEST,
                        filePath,
                        null
                ));
            }
        }
        return result;
    }

    private Set<String> parsePackageJsonDeps(String content) {
        if (content == null || content.isBlank()) return Set.of();
        try {
            JsonNode root = JSON.readTree(content);
            Set<String> result = new HashSet<>();
            for (String field : DEP_FIELDS) {
                JsonNode node = root.get(field);
                if (node != null && node.isObject()) {
                    Iterator<String> keys = node.fieldNames();
                    while (keys.hasNext()) {
                        result.add(keys.next());
                    }
                }
            }
            return result;
        } catch (Exception e) {
            log.debug("Failed to parse package.json deps: {}", e.getMessage());
            return Set.of();
        }
    }

    private Map<String, String> parsePackageJsonDepsWithVersion(String content) {
        if (content == null || content.isBlank()) return Map.of();
        try {
            JsonNode root = JSON.readTree(content);
            // Preserve insertion order for deterministic tests
            java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
            for (String field : DEP_FIELDS) {
                JsonNode node = root.get(field);
                if (node != null && node.isObject()) {
                    Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                    while (fields.hasNext()) {
                        Map.Entry<String, JsonNode> e = fields.next();
                        result.putIfAbsent(e.getKey(), e.getValue().asText());
                    }
                }
            }
            return result;
        } catch (Exception e) {
            log.debug("Failed to parse package.json deps with version: {}", e.getMessage());
            return Map.of();
        }
    }

    // ─────────────────────────────── Source files ────────────────────────────

    private List<ExtractedDep> extractSourceAdded(String filePath, String oldContent, String newContent) {
        Set<String> oldSpecifiers = extractBareSpecifiers(oldContent == null ? "" : oldContent);
        Set<String> newSpecifiers = extractBareSpecifiers(newContent);

        List<ExtractedDep> result = new ArrayList<>();
        for (String spec : newSpecifiers) {
            if (!oldSpecifiers.contains(spec)) {
                result.add(new ExtractedDep(
                        ExtractedDep.ECOSYSTEM_NPM,
                        spec,
                        null,
                        ExtractedDep.SOURCE_IMPORT,
                        filePath,
                        null
                ));
            }
        }
        return result;
    }

    /**
     * 从源码中提取所有 bare specifier（过滤相对路径、node: 前缀、内置模块）。
     * 顺序使用 LinkedHashSet 方便测试断言。
     */
    Set<String> extractBareSpecifiers(String content) {
        if (content == null || content.isBlank()) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        Matcher m = IMPORT_PATTERN.matcher(content);
        while (m.find()) {
            String spec = m.group(1);
            if (spec == null || spec.isBlank()) continue;
            if (isFiltered(spec)) continue;
            // Normalize: scoped @scope/name → keep full; non-scoped → first segment
            String pkgName = normalizePkgName(spec);
            if (pkgName != null) {
                result.add(pkgName);
            }
        }
        return result;
    }

    private boolean isFiltered(String spec) {
        // Relative paths
        if (spec.startsWith("./") || spec.startsWith("../") || spec.startsWith("/")) return true;
        // node: protocol
        if (spec.startsWith("node:")) return true;
        // Built-in modules (exact match or with subpath like "fs/promises")
        String base = spec.contains("/") ? spec.substring(0, spec.indexOf('/')) : spec;
        if (NODE_BUILTINS.contains(base)) return true;
        return false;
    }

    /** 提取包名：scoped 包取 @scope/name，非 scoped 取第一个路径段。 */
    String normalizePkgName(String spec) {
        if (spec == null || spec.isBlank()) return null;
        if (spec.startsWith("@")) {
            // @scope/name[/subpath] → @scope/name
            int slash2 = spec.indexOf('/', 1);
            if (slash2 < 0) return null; // malformed scoped
            int slash3 = spec.indexOf('/', slash2 + 1);
            return slash3 < 0 ? spec : spec.substring(0, slash3);
        } else {
            // name[/subpath] → name
            int slash = spec.indexOf('/');
            return slash < 0 ? spec : spec.substring(0, slash);
        }
    }
}
