package com.repolens.service.impl.support;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.repolens.config.DependencyCheckProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java dependency extractor.
 * <ul>
 *   <li><b>pom.xml</b>: DOM parsing, extracts &lt;dependency&gt; groupId:artifactId:version.</li>
 *   <li><b>build.gradle / build.gradle.kts</b>: regex extracts GAV coordinate strings.</li>
 *   <li><b>.java source</b>: JavaParser cu.getImports(), filters JDK namespaces
 *       (java.* / javax.* / jdk.* / sun.*) and the configured project base package.</li>
 * </ul>
 *
 * <p>Returns only deps added in newContent but absent from oldContent (diff semantics).
 * All parse failures are caught silently and return an empty list.
 */
@Slf4j
@Component
public class JavaDependencyExtractor implements DependencyExtractor {

    /** JDK 内置命名空间前缀，始终过滤。 */
    private static final Set<String> JDK_PREFIXES = Set.of("java.", "javax.", "jdk.", "sun.");

    /**
     * Gradle 依赖声明正则：匹配
     * {@code implementation 'com.google.guava:guava:31.0-jre'}
     * {@code testImplementation("org.junit.jupiter:junit-jupiter:5.9.0")}
     * 等多种格式。捕获组 1 = GAV 字符串。
     */
    private static final Pattern GRADLE_DEP = Pattern.compile(
            "(?:implementation|api|compileOnly|runtimeOnly|testImplementation|" +
            "testRuntimeOnly|annotationProcessor|kapt|classpath)" +
            "\\s*[\\(]?\\s*[\"']([A-Za-z0-9._\\-]+:[A-Za-z0-9._\\-]+(?::[^\"'\\s]*)?)[ \"']",
            Pattern.MULTILINE
    );

    private final DependencyCheckProperties properties;

    public JavaDependencyExtractor(DependencyCheckProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean supports(String filePath) {
        if (filePath == null) return false;
        String lower = filePath.toLowerCase(Locale.ROOT);
        return lower.endsWith("pom.xml")
                || lower.endsWith("build.gradle")
                || lower.endsWith("build.gradle.kts")
                || lower.endsWith(".java");
    }

    @Override
    public List<ExtractedDep> extractAdded(String filePath, String oldContent, String newContent) {
        if (filePath == null || newContent == null) return List.of();
        String lower = filePath.toLowerCase(Locale.ROOT);
        try {
            if (lower.endsWith("pom.xml")) {
                return extractPomAdded(filePath, oldContent, newContent);
            } else if (lower.endsWith("build.gradle") || lower.endsWith("build.gradle.kts")) {
                return extractGradleAdded(filePath, oldContent, newContent);
            } else if (lower.endsWith(".java")) {
                return extractJavaAdded(filePath, oldContent, newContent);
            }
        } catch (Exception ex) {
            log.debug("JavaDependencyExtractor failed for {}: {}", filePath, ex.getMessage());
        }
        return List.of();
    }

    // ─────────────────────────────── pom.xml ─────────────────────────────────

    /**
     * DOM 解析 pom.xml，提取 &lt;dependency&gt; 坐标。
     * XXE 保护：禁用外部实体和 DOCTYPE。
     */
    List<ExtractedDep> extractPomAdded(String filePath, String oldContent, String newContent) {
        Set<String> oldCoords = parsePomCoords(oldContent);
        List<PomDep> newDeps = parsePomDeps(newContent);

        List<ExtractedDep> result = new ArrayList<>();
        for (PomDep dep : newDeps) {
            if (!oldCoords.contains(dep.coord())) {
                result.add(new ExtractedDep(
                        ExtractedDep.ECOSYSTEM_MAVEN,
                        dep.coord(),
                        dep.version(),
                        ExtractedDep.SOURCE_MANIFEST,
                        filePath,
                        null
                ));
            }
        }
        return result;
    }

    private Set<String> parsePomCoords(String content) {
        Set<String> result = new LinkedHashSet<>();
        for (PomDep dep : parsePomDeps(content)) {
            result.add(dep.coord());
        }
        return result;
    }

    private List<PomDep> parsePomDeps(String content) {
        List<PomDep> result = new ArrayList<>();
        if (content == null || content.isBlank()) return result;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // XXE protection
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            // Suppress parser error output
            builder.setErrorHandler(null);
            Document doc = builder.parse(
                    new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
            NodeList deps = doc.getElementsByTagName("dependency");
            for (int i = 0; i < deps.getLength(); i++) {
                Element dep = (Element) deps.item(i);
                String gId = textOf(dep, "groupId");
                String aId = textOf(dep, "artifactId");
                String ver = textOf(dep, "version");
                if (gId != null && aId != null && !gId.isBlank() && !aId.isBlank()) {
                    result.add(new PomDep(gId.trim() + ":" + aId.trim(), ver != null ? ver.trim() : null));
                }
            }
        } catch (Exception ex) {
            log.debug("pom.xml parse failed: {}", ex.getMessage());
        }
        return result;
    }

    private String textOf(Element parent, String tagName) {
        NodeList nl = parent.getElementsByTagName(tagName);
        if (nl.getLength() == 0) return null;
        return nl.item(0).getTextContent();
    }

    /** 不可变值对象：pom dependency (groupId:artifactId, version)。 */
    record PomDep(String coord, String version) {}

    // ─────────────────────────── build.gradle ────────────────────────────────

    List<ExtractedDep> extractGradleAdded(String filePath, String oldContent, String newContent) {
        Set<String> oldCoords = parseGradleCoords(oldContent == null ? "" : oldContent);
        List<GradleDep> newDeps = parseGradleDeps(newContent);

        List<ExtractedDep> result = new ArrayList<>();
        for (GradleDep dep : newDeps) {
            if (!oldCoords.contains(dep.coord())) {
                result.add(new ExtractedDep(
                        ExtractedDep.ECOSYSTEM_MAVEN,
                        dep.coord(),
                        dep.version(),
                        ExtractedDep.SOURCE_MANIFEST,
                        filePath,
                        null
                ));
            }
        }
        return result;
    }

    private Set<String> parseGradleCoords(String content) {
        Set<String> result = new LinkedHashSet<>();
        for (GradleDep dep : parseGradleDeps(content)) {
            result.add(dep.coord());
        }
        return result;
    }

    private List<GradleDep> parseGradleDeps(String content) {
        List<GradleDep> result = new ArrayList<>();
        if (content == null || content.isBlank()) return result;
        Matcher m = GRADLE_DEP.matcher(content);
        while (m.find()) {
            String gav = m.group(1);
            if (gav == null || gav.isBlank()) continue;
            String[] parts = gav.split(":", 3);
            if (parts.length < 2) continue;
            String groupId = parts[0].trim();
            String artifactId = parts[1].trim();
            String version = parts.length == 3 ? parts[2].trim() : null;
            if (!groupId.isBlank() && !artifactId.isBlank()) {
                result.add(new GradleDep(groupId + ":" + artifactId, version));
            }
        }
        return result;
    }

    /** 不可变值对象：gradle dependency (groupId:artifactId, version)。 */
    record GradleDep(String coord, String version) {}

    // ───────────────────────────── .java source ──────────────────────────────

    List<ExtractedDep> extractJavaAdded(String filePath, String oldContent, String newContent) {
        Set<String> oldImports = parseJavaImports(oldContent == null ? "" : oldContent);
        Set<String> newImports = parseJavaImports(newContent);

        List<ExtractedDep> result = new ArrayList<>();
        for (String fqn : newImports) {
            if (!oldImports.contains(fqn)) {
                result.add(new ExtractedDep(
                        ExtractedDep.ECOSYSTEM_MAVEN,
                        fqn,
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
     * 使用 JavaParser 解析 Java 源文件中的 import 语句。
     * 过滤：JDK 前缀 + 配置的项目基包。
     * 返回全限定类名（含通配符 import 则取 "pkg.*" 形式）。
     * 解析失败静默返回空集合。
     */
    Set<String> parseJavaImports(String content) {
        Set<String> result = new LinkedHashSet<>();
        if (content == null || content.isBlank()) return result;
        try {
            CompilationUnit cu = StaticJavaParser.parse(content);
            for (ImportDeclaration imp : cu.getImports()) {
                String name = imp.getNameAsString();
                if (imp.isAsterisk()) {
                    name = name + ".*";
                }
                if (isFilteredImport(name)) continue;
                result.add(name);
            }
        } catch (Exception ex) {
            log.debug("JavaParser parse failed: {}", ex.getMessage());
        }
        return result;
    }

    private boolean isFilteredImport(String fqn) {
        if (fqn == null) return true;
        // Filter JDK built-in namespaces
        for (String prefix : JDK_PREFIXES) {
            if (fqn.startsWith(prefix)) return true;
        }
        // Filter project's own base package (if configured)
        String base = properties.getJavaBasePackage();
        if (base != null && !base.isBlank() && fqn.startsWith(base)) return true;
        return false;
    }
}
