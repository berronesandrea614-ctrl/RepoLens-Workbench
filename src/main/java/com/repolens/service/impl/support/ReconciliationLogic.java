package com.repolens.service.impl.support;

import java.util.Collection;
import java.util.List;

/**
 * 对账算法纯函数（Feature B P1）。
 * 全确定性、无 Spring 依赖、便于 TDD 单测。
 *
 * <h2>路径归一化优先级</h2>
 * 全等 > 路径后缀等（带 / 边界）> 类名等（去扩展名，大小写不敏感）。
 * 与 {@code RequirementInsightServiceImpl.pathMatches} 逻辑一致。
 *
 * <h2>同源判定（OVER_SCOPE vs SILENT_ADD）</h2>
 * 同源 = actual 与某声明文件的最长公共目录前缀 ≥ 1 层。
 * 歧义时宁判 SILENT_ADD（更醒目）。
 */
public final class ReconciliationLogic {

    // 计划项状态常量
    public static final String LANDED           = "LANDED";
    public static final String PARTIAL          = "PARTIAL";
    public static final String MISSING_ATTEMPTED = "MISSING_ATTEMPTED";
    public static final String MISSING_SILENT   = "MISSING_SILENT";

    // 改动分类常量
    public static final String IN_PLAN     = "IN_PLAN";
    public static final String OVER_SCOPE  = "OVER_SCOPE";
    public static final String SILENT_ADD  = "SILENT_ADD";

    private ReconciliationLogic() {}

    // ── 四态计划项判定 ───────────────────────────────────────────────────────

    /**
     * 判定一个计划步的四态状态。
     *
     * @param declaredFiles 该步声明的文件列表
     * @param actualFiles   该 session 实际改动的文件路径集合
     * @param toolReadFiles 该 session agent_run_step 读过的文件路径集合
     * @return LANDED / PARTIAL / MISSING_ATTEMPTED / MISSING_SILENT
     */
    public static String determinePlanItemStatus(
            List<String> declaredFiles,
            Collection<String> actualFiles,
            Collection<String> toolReadFiles) {

        if (declaredFiles == null || declaredFiles.isEmpty()) {
            // 没有声明文件，无法判定，降级为 MISSING_SILENT
            return MISSING_SILENT;
        }

        int declared = declaredFiles.size();
        int landed   = 0;
        for (String df : declaredFiles) {
            if (isInCollection(df, actualFiles)) landed++;
        }

        if (landed == declared) return LANDED;
        if (landed > 0)          return PARTIAL;

        // landed == 0: MISSING
        // 检查是否有读工具读过声明文件
        for (String df : declaredFiles) {
            if (isInCollection(df, toolReadFiles)) return MISSING_ATTEMPTED;
        }
        return MISSING_SILENT;
    }

    /** 判断声明文件是否命中实际文件集合（用 pathMatches 逐一比对）。 */
    private static boolean isInCollection(String declared, Collection<String> actualFiles) {
        if (actualFiles == null) return false;
        for (String actual : actualFiles) {
            if (pathMatches(actual, declared)) return true;
        }
        return false;
    }

    // ── 改动分类 ─────────────────────────────────────────────────────────────

    /**
     * 对一个实际改动文件分类（IN_PLAN / OVER_SCOPE / SILENT_ADD）。
     *
     * @param actualFilePath 实际改动的文件路径
     * @param allDeclaredFiles 所有计划步的声明文件列表（扁平）
     * @return IN_PLAN / OVER_SCOPE / SILENT_ADD
     */
    public static String classifyChange(String actualFilePath, List<String> allDeclaredFiles) {
        if (allDeclaredFiles == null || allDeclaredFiles.isEmpty()) {
            return SILENT_ADD;
        }
        // 精确命中某声明文件
        for (String df : allDeclaredFiles) {
            if (pathMatches(actualFilePath, df)) return IN_PLAN;
        }
        // 同源（同目录前缀 ≥ 1 层）
        for (String df : allDeclaredFiles) {
            if (isSameSource(actualFilePath, df)) return OVER_SCOPE;
        }
        return SILENT_ADD;
    }

    /**
     * 同源判定：actual 与 declared 是否在同一目录（前缀 ≥ 1 层）。
     * 歧义或无法判定时返回 false（宁判 SILENT_ADD）。
     */
    public static boolean isSameSource(String actual, String declared) {
        if (actual == null || declared == null) return false;
        String a = normalizePath(actual);
        String d = normalizePath(declared);
        String aDir = parentDir(a);
        String dDir = parentDir(d);
        if (aDir.isEmpty() || dDir.isEmpty()) return false;
        // 同目录 or a 在 d 的子目录 or 反之
        return aDir.equals(dDir) || aDir.startsWith(dDir + "/") || dDir.startsWith(aDir + "/");
    }

    // ── 路径工具 ─────────────────────────────────────────────────────────────

    /**
     * 路径匹配：actual 是否"属于" declared。
     * 逻辑与 {@code RequirementInsightServiceImpl.pathMatches} 完全一致（复制保持独立可测）。
     *
     * <p>declared 可能是：相对路径（src/A.java）或类名（SecurityConfig）。
     */
    public static boolean pathMatches(String actual, String declared) {
        if (actual == null || declared == null) return false;
        String a = actual.replace('\\', '/');
        String d = declared.replace('\\', '/');
        if (a.equals(d)) return true;
        if (a.endsWith("/" + d)) return true;
        if (!d.contains("/")) {
            String base = fileBaseName(a);
            return base.equalsIgnoreCase(d) || base.equalsIgnoreCase(d.replace(".java", ""));
        }
        return false;
    }

    /** 取路径末段（文件名），去除 .java/.kt/.ts/.tsx 扩展名。 */
    public static String fileBaseName(String path) {
        if (path == null) return "";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String normalizePath(String path) {
        return path.replace('\\', '/').toLowerCase();
    }

    /** 取路径的父目录部分（不含末段文件名）。 */
    private static String parentDir(String normalizedPath) {
        int slash = normalizedPath.lastIndexOf('/');
        return slash > 0 ? normalizedPath.substring(0, slash) : "";
    }

    // ── 断言计数 ─────────────────────────────────────────────────────────────

    /**
     * 计算一段 Java/JUnit 测试代码中的「断言 + 测试数量」。
     * 用于 TEST_WEAKENED 检测：旧断言数 - 新断言数 &gt; 0 即判定为净减少。
     *
     * <p>计数规则：
     * <ul>
     *   <li>{@code assert*(} — AssertJ/JUnit assert 方法调用。</li>
     *   <li>{@code @Test} — JUnit 测试用例注解。</li>
     *   <li>{@code @Disabled} — 被禁用（算「弱化」信号，加权 1）。</li>
     *   <li>{@code .skip(} / {@code xit(} — JS/TS 跳过断言。</li>
     * </ul>
     */
    public static int countAssertions(String content) {
        if (content == null || content.isBlank()) return 0;
        int count = 0;
        for (String line : content.split("\n", -1)) {
            String t = line.trim();
            // assert 方法调用（Java/JUnit/AssertJ）
            if (t.contains("assert") && (t.contains("assert(") || t.contains("Assert")
                    || t.contains("assertEquals") || t.contains("assertTrue")
                    || t.contains("assertFalse") || t.contains("assertNotNull")
                    || t.contains("assertNull") || t.contains("assertThat")
                    || t.contains("assertThrows"))) {
                count++;
            }
            // @Test 注解
            if (t.startsWith("@Test")) count++;
            // @Disabled
            if (t.startsWith("@Disabled")) count++;
            // JS .skip / xit
            if (t.contains(".skip(") || t.startsWith("xit(")) count++;
        }
        return count;
    }

    /**
     * 判断一个文件路径是否是测试文件。
     * 路径中含 /test/ 或 Test.java 结尾，或含 .test.ts/.spec.ts 等。
     */
    public static boolean isTestFile(String filePath) {
        if (filePath == null) return false;
        String p = filePath.replace('\\', '/').toLowerCase();
        return p.contains("/test/") || p.contains("/tests/")
                || p.endsWith("test.java") || p.endsWith("tests.java")
                || p.contains("test.ts") || p.contains("spec.ts")
                || p.contains("test.js") || p.contains("spec.js");
    }
}
