package com.repolens.service.impl.support;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 纯静态工具类：解析文件内容（file_change_log.new_content）提取结构化摘要，
 * 用于 RequirementInsightServiceImpl 在无 code_symbol 行时丰富 FlowNodeVO 的 sig/note 字段。
 *
 * <p><b>设计原则</b>：纯静态，无 Spring 依赖，易于单元测试；失败安全（所有异常捕获，
 * 失败返回 {@link FileSummary#EMPTY}）；确定性，无 LLM 调用。
 *
 * <p><b>大小限制</b>：超过 200 KB 的文件跳过解析，返回 EMPTY，避免拖慢 insight 接口。
 * sig ≤ 120 字符，note ≤ 160 字符，节点卡片紧凑显示。
 */
@Slf4j
public final class FileContentSummarizer {

    /** 文件内容大小上限（超出跳过解析）。 */
    static final int MAX_CONTENT_BYTES = 200 * 1024;

    /** sig 字符长度上限。 */
    static final int MAX_SIG_LENGTH = 120;

    /** note 字符长度上限。 */
    static final int MAX_NOTE_LENGTH = 160;

    /** 实体类 sig 中最多展示的字段数（超出后加 "..."）。 */
    private static final int MAX_ENTITY_FIELDS = 6;

    /** 服务/控制器 sig 中最多展示的方法签名数。 */
    private static final int MAX_SERVICE_METHODS = 3;

    private FileContentSummarizer() {
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 文件内容结构化摘要。
     *
     * @param className 解析到的类名（或 null）
     * @param sig       一句话签名，如 "class Blog { title, content, author... }"
     * @param note      中文业务描述，如 "Blog 实体类：含 title/content/author 等字段"
     */
    public record FileSummary(String className, String sig, String note) {
        /** 解析失败或内容为空时的单例返回值。 */
        public static final FileSummary EMPTY = new FileSummary(null, null, null);
    }

    /**
     * 根据文件路径和内容生成结构化摘要。
     *
     * <ul>
     *   <li>.java 文件：使用 JavaParser 解析；</li>
     *   <li>其他文件：轻量 heuristic（注释/行数/方法计数）；</li>
     *   <li>内容超过 200 KB：直接返回 EMPTY；</li>
     *   <li>任何异常：静默返回 EMPTY。</li>
     * </ul>
     *
     * @param filePath 文件路径（用于扩展名判断）
     * @param content  文件全量内容（new_content）
     */
    public static FileSummary summarize(String filePath, String content) {
        if (filePath == null || content == null || content.isEmpty()) {
            return FileSummary.EMPTY;
        }
        if (content.length() > MAX_CONTENT_BYTES) {
            return FileSummary.EMPTY;
        }
        try {
            String lower = filePath.toLowerCase();
            if (lower.endsWith(".java")) {
                return summarizeJava(content);
            }
            return summarizeFallback(filePath, content);
        } catch (Exception ex) {
            log.debug("FileContentSummarizer.summarize failed, path={}: {}", filePath, ex.getMessage());
            return FileSummary.EMPTY;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Java parsing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 用 JavaParser 解析 Java 源码，提取主类型的 className / sig / note。
     * 不使用 SymbolSolver（仅需结构信息，无需跨文件类型推断）。
     *
     * <p>包级可见以便单元测试直接调用。
     */
    static FileSummary summarizeJava(String javaContent) {
        try {
            JavaParser parser = new JavaParser();
            ParseResult<CompilationUnit> result = parser.parse(javaContent);
            if (result.getResult().isEmpty()) {
                return FileSummary.EMPTY;
            }
            CompilationUnit cu = result.getResult().get();
            TypeDeclaration<?> primary = findPrimaryType(cu);
            if (primary == null) {
                return FileSummary.EMPTY;
            }

            String className = primary.getNameAsString();
            String javadoc   = extractJavadoc(primary);

            String sig;
            String note;

            if (isEntityLike(primary)) {
                List<String> fields = extractFieldNames(primary);
                sig  = buildEntitySig(className, fields);
                note = buildEntityNote(className, fields, javadoc);
            } else {
                List<String> methods = extractNonTrivialMethodSigs(primary);
                sig  = buildTypeSig(className, primary, methods);
                note = buildTypeNote(className, primary, methods, javadoc);
            }

            return new FileSummary(
                    className,
                    truncate(sig,  MAX_SIG_LENGTH),
                    truncate(note, MAX_NOTE_LENGTH));
        } catch (Exception ex) {
            log.debug("summarizeJava failed: {}", ex.getMessage());
            return FileSummary.EMPTY;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fallback (non-Java)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 非 Java 文件的轻量 heuristic：
     * 尝试提取首个有意义注释作为 sig/note，否则回退到行数+方法/函数计数。
     *
     * <p>包级可见以便单元测试直接调用。
     */
    static FileSummary summarizeFallback(String filePath, String content) {
        try {
            int lines = content.split("\n", -1).length;
            String ext = fileExtension(filePath);
            int methodCount = countFunctions(content, ext);
            String baseName = fileBaseName(filePath);

            String firstComment = extractFirstComment(content);
            String sig;
            String note;

            if (firstComment != null && !firstComment.isBlank()) {
                sig  = firstComment;
                note = baseName + "：" + firstComment;
            } else {
                sig  = lines + " 行，" + methodCount + " 个方法/函数";
                note = baseName + "：" + lines + " 行";
            }
            return new FileSummary(null, truncate(sig, MAX_SIG_LENGTH), truncate(note, MAX_NOTE_LENGTH));
        } catch (Exception ex) {
            return FileSummary.EMPTY;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Java helper methods (package-visible for tests)
    // ─────────────────────────────────────────────────────────────────────────

    /** 找 CompilationUnit 的第一个顶层类型声明。 */
    static TypeDeclaration<?> findPrimaryType(CompilationUnit cu) {
        // getTypes() returns only top-level (non-nested) types
        if (cu.getTypes().isEmpty()) return null;
        return cu.getTypes().get(0);
    }

    /** 提取类型的 Javadoc 注释文本（仅类级注释）。 */
    static String extractJavadoc(TypeDeclaration<?> type) {
        try {
            Optional<com.github.javaparser.ast.comments.JavadocComment> jd =
                    type.getJavadocComment();
            if (jd.isPresent()) {
                String raw = jd.get().getContent();
                // Strip leading "* " from each line, drop @tag lines
                String cleaned = Pattern.compile("(?m)^\\s*\\*\\s?").matcher(raw)
                        .replaceAll(" ");
                cleaned = Pattern.compile("@\\w+[^\n]*").matcher(cleaned)
                        .replaceAll("");
                cleaned = cleaned.replaceAll("\\s+", " ").trim();
                if (cleaned.length() > 4) return cleaned;
            }
        } catch (Exception ex) {
            // ignore
        }
        return null;
    }

    /**
     * 判断是否是实体/POJO 类（用字段列表展示 sig）。
     * 启发规则：名称后缀匹配，或字段多且没有非 accessor 方法。
     */
    static boolean isEntityLike(TypeDeclaration<?> type) {
        String name = type.getNameAsString();
        if (name.endsWith("Entity") || name.endsWith("VO") || name.endsWith("DTO")
                || name.endsWith("DO") || name.endsWith("PO") || name.endsWith("Request")
                || name.endsWith("Response") || name.endsWith("Model")) {
            return true;
        }
        int fieldCount  = type.getFields().size();
        long nonAccessor = type.getMethods().stream()
                .filter(m -> {
                    String mn = m.getNameAsString();
                    // Only count methods that are NOT trivial accessors
                    boolean trivialGet = (mn.startsWith("get") || mn.startsWith("is"))
                            && m.getParameters().isEmpty();
                    boolean trivialSet = mn.startsWith("set") && m.getParameters().size() == 1;
                    boolean objectMethod = mn.equals("equals") || mn.equals("hashCode")
                            || mn.equals("toString");
                    return !trivialGet && !trivialSet && !objectMethod;
                })
                .count();
        return fieldCount > 0 && nonAccessor <= 2;
    }

    /** 提取非静态字段的名称列表。 */
    static List<String> extractFieldNames(TypeDeclaration<?> type) {
        List<String> names = new ArrayList<>();
        for (FieldDeclaration field : type.getFields()) {
            if (field.isStatic()) continue;
            for (VariableDeclarator var : field.getVariables()) {
                names.add(var.getNameAsString());
            }
        }
        return names;
    }

    /**
     * 提取非 getter/setter/equals/hashCode/toString 方法的简要签名。
     * 最多 MAX_SERVICE_METHODS 条，优先取 public 方法。
     */
    static List<String> extractNonTrivialMethodSigs(TypeDeclaration<?> type) {
        List<String> sigs = new ArrayList<>();
        for (MethodDeclaration method : type.getMethods()) {
            String mn = method.getNameAsString();
            // Skip canonical accessor patterns (no-param getter, one-param setter, no-param is*).
            // Methods like getById(Long id) have params so they are NOT trivial getters.
            boolean isTrivialGetter = (mn.startsWith("get") || mn.startsWith("is"))
                    && method.getParameters().isEmpty();
            boolean isTrivialSetter = mn.startsWith("set")
                    && method.getParameters().size() == 1;
            boolean isObjectMethod = mn.equals("equals") || mn.equals("hashCode")
                    || mn.equals("toString");
            if (isTrivialGetter || isTrivialSetter || isObjectMethod) {
                continue;
            }
            String retType = method.getType().asString();
            String params  = method.getParameters().stream()
                    .map(p -> p.getType().asString())
                    .collect(Collectors.joining(", "));
            sigs.add(retType + " " + mn + "(" + params + ")");
            if (sigs.size() >= MAX_SERVICE_METHODS) break;
        }
        // If nothing found but there are methods (e.g. all are accessors), grab first few
        if (sigs.isEmpty() && !type.getMethods().isEmpty()) {
            for (MethodDeclaration m : type.getMethods()) {
                sigs.add(m.getType().asString() + " " + m.getNameAsString() + "(...)");
                if (sigs.size() >= MAX_SERVICE_METHODS) break;
            }
        }
        return sigs;
    }

    private static String buildEntitySig(String className, List<String> fields) {
        if (fields.isEmpty()) return "class " + className;
        List<String> shown = fields.size() > MAX_ENTITY_FIELDS
                ? fields.subList(0, MAX_ENTITY_FIELDS)
                : fields;
        String fieldStr = String.join(", ", shown);
        if (fields.size() > MAX_ENTITY_FIELDS) fieldStr += "...";
        return "class " + className + " { " + fieldStr + " }";
    }

    private static String buildEntityNote(String className, List<String> fields, String javadoc) {
        if (javadoc != null) return javadoc;
        if (fields.isEmpty()) return className + " 实体类";
        List<String> shown = fields.size() > 4 ? fields.subList(0, 4) : fields;
        String fieldStr = String.join("/", shown);
        if (fields.size() > 4) fieldStr += " 等";
        return className + " 实体类：含 " + fieldStr + " 字段";
    }

    private static String buildTypeSig(String className, TypeDeclaration<?> type,
                                        List<String> methods) {
        String typeKind = "class";
        if (type instanceof ClassOrInterfaceDeclaration coi) {
            typeKind = coi.isInterface() ? "interface" : "class";
        } else if (type instanceof EnumDeclaration) {
            typeKind = "enum";
        }
        if (methods.isEmpty()) return typeKind + " " + className;
        return typeKind + " " + className + " { " + String.join("; ", methods) + " }";
    }

    private static String buildTypeNote(String className, TypeDeclaration<?> type,
                                         List<String> methods, String javadoc) {
        if (javadoc != null) return javadoc;
        if (className.endsWith("Controller")) return className + "：HTTP 接口层";
        if (className.endsWith("ServiceImpl") || className.endsWith("Service"))
            return className + "：业务逻辑层";
        if (className.endsWith("Mapper") || className.endsWith("Repository")
                || className.endsWith("Dao"))
            return className + "：数据访问层";
        if (className.endsWith("Config") || className.endsWith("Configuration"))
            return className + "：配置类";
        if (className.endsWith("Util") || className.endsWith("Utils")
                || className.endsWith("Helper"))
            return className + "：工具类";
        if (!methods.isEmpty()) {
            return className + "：含 " + methods.size() + " 个方法";
        }
        return className + " 类";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fallback helper methods
    // ─────────────────────────────────────────────────────────────────────────

    /** 提取文件内容中第一个有意义的注释行。 */
    static String extractFirstComment(String content) {
        // Single-line: // xxx
        Matcher m = Pattern.compile("//\\s*(.+)").matcher(content);
        if (m.find()) {
            String c = m.group(1).trim();
            if (!c.isEmpty()) return c;
        }
        // Block comment opening: /* xxx */
        m = Pattern.compile("/\\*+\\s*([^*\n/][^\n]*)").matcher(content);
        if (m.find()) {
            String c = m.group(1).trim();
            if (!c.isEmpty()) return c;
        }
        // Hash comment: # xxx (Python / Shell / YAML)
        m = Pattern.compile("#\\s*([^!\n][^\n]*)").matcher(content);
        if (m.find()) {
            String c = m.group(1).trim();
            if (!c.isEmpty()) return c;
        }
        return null;
    }

    /** 用正则粗略统计文件中的函数/方法数量。 */
    static int countFunctions(String content, String ext) {
        try {
            String patternStr = switch (ext) {
                case "js", "ts", "jsx", "tsx" ->
                        "(?m)\\b(function\\s+\\w+|\\w+\\s*[=:]\\s*(async\\s+)?function|async\\s+\\w+\\s*\\()";
                case "py" -> "(?m)^\\s*def\\s+\\w+";
                default ->
                        "(?m)^\\s*(public|private|protected)?\\s+(static\\s+)?(\\w[\\w<>\\[\\]]+\\s+)\\w+\\s*\\(";
            };
            Matcher m = Pattern.compile(patternStr).matcher(content);
            int count = 0;
            while (m.find()) count++;
            return count;
        } catch (Exception ex) {
            return 0;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // String utilities
    // ─────────────────────────────────────────────────────────────────────────

    private static String fileExtension(String filePath) {
        if (filePath == null) return "";
        int dot = filePath.lastIndexOf('.');
        return dot >= 0 ? filePath.substring(dot + 1).toLowerCase() : "";
    }

    private static String fileBaseName(String filePath) {
        if (filePath == null) return "";
        int slash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        String name = slash >= 0 ? filePath.substring(slash + 1) : filePath;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    static String truncate(String s, int maxLen) {
        if (s == null) return null;
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 1) + "…";
    }
}
