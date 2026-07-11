package com.repolens.service.impl.support;

/**
 * 从文件中抽取到的单个依赖候选。不可变值对象。
 *
 * @param ecosystem  npm 或 pypi
 * @param name       包名（已按各生态规范归一化）
 * @param version    版本（可能为 null，仅 MANIFEST 级可靠）
 * @param source     MANIFEST（清单文件）或 IMPORT（源码导入）
 * @param filePath   来源文件路径
 * @param line       在文件中的行号（可能为 null）
 */
public record ExtractedDep(
        String ecosystem,
        String name,
        String version,
        String source,
        String filePath,
        Integer line
) {
    public static final String ECOSYSTEM_NPM = "npm";
    public static final String ECOSYSTEM_PYPI = "pypi";
    public static final String ECOSYSTEM_MAVEN = "maven";

    public static final String SOURCE_MANIFEST = "MANIFEST";
    public static final String SOURCE_IMPORT = "IMPORT";
}
