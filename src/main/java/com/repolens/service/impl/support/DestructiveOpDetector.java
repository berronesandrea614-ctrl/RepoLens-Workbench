package com.repolens.service.impl.support;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 纯逻辑组件：检测一条变更的破坏性风险。
 * <p>
 * - DELETE_FILE: opType=="DELETE" → 权威判定，在任何 try 之外，绝不漏。
 * - 其余规则: 扫 newContent 正则，fail-safe（宁漏不崩）。
 * - 去重: 同 ruleCode 只保留第一条。
 */
@Component
public class DestructiveOpDetector {

    public record RiskFinding(String category, String ruleCode, String severity,
                              String reversibility, String evidence) {}

    // ── 正则（全 CASE_INSENSITIVE） ───────────────────────────────────────────
    private static final Pattern DROP_PATTERN =
            Pattern.compile("DROP\\s+(TABLE|DATABASE|SCHEMA)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern TRUNCATE_PATTERN =
            Pattern.compile("TRUNCATE\\s+(TABLE\\s+)?\\w", Pattern.CASE_INSENSITIVE);

    // 匹配 DELETE FROM <table> 后跟 ; 或行尾（无 WHERE 子句）
    private static final Pattern DELETE_NO_WHERE_PATTERN =
            Pattern.compile("DELETE\\s+FROM\\s+\\w+\\s*(;|$)", Pattern.CASE_INSENSITIVE);

    // rm -rf / rm -fr 各种组合（如 rm -Rf, rm -fR 等）
    private static final Pattern RM_RF_PATTERN =
            Pattern.compile("rm\\s+-[a-z]*r[a-z]*f|rm\\s+-[a-z]*f[a-z]*r", Pattern.CASE_INSENSITIVE);

    // ── 主入口 ────────────────────────────────────────────────────────────────

    /**
     * 分析单条变更，返回破坏性风险列表（可空 list，绝不抛异常）。
     *
     * @param opType     操作类型（DELETE / WRITE / CREATE …）
     * @param filePath   文件路径
     * @param oldContent 变更前内容（可为 null）
     * @param newContent 变更后内容（可为 null）
     * @return 风险列表，同 ruleCode 去重
     */
    public List<RiskFinding> detect(String opType, String filePath,
                                    String oldContent, String newContent) {
        List<RiskFinding> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // ── DELETE_FILE: 权威判定，在 try 之外，绝不漏 ────────────────────────
        if ("DELETE".equals(opType)) {
            String evidence = isMigrationFile(filePath) ? "删除迁移文件" : filePath;
            addUnique(results, seen,
                    new RiskFinding("DESTRUCTIVE", "DELETE_FILE", "BLOCK", "IRREVERSIBLE", evidence));
        }

        // ── 正则规则：fail-safe，各规则独立 try-catch，不影响 DELETE ─────────
        String content = newContent != null ? newContent : "";
        String[] lines = content.split("\n", -1);

        // DROP_TABLE_DB
        try {
            for (String line : lines) {
                if (DROP_PATTERN.matcher(line).find()) {
                    addUnique(results, seen,
                            new RiskFinding("DESTRUCTIVE", "DROP_TABLE_DB",
                                    "BLOCK", "IRREVERSIBLE", line.trim()));
                    break;
                }
            }
        } catch (Exception ignored) { /* 跳过该规则 */ }

        // TRUNCATE
        try {
            for (String line : lines) {
                if (TRUNCATE_PATTERN.matcher(line).find()) {
                    addUnique(results, seen,
                            new RiskFinding("DESTRUCTIVE", "TRUNCATE",
                                    "BLOCK", "IRREVERSIBLE", line.trim()));
                    break;
                }
            }
        } catch (Exception ignored) { /* 跳过该规则 */ }

        // DELETE_NO_WHERE
        try {
            for (String line : lines) {
                if (DELETE_NO_WHERE_PATTERN.matcher(line).find()) {
                    addUnique(results, seen,
                            new RiskFinding("DESTRUCTIVE", "DELETE_NO_WHERE",
                                    "BLOCK", "IRREVERSIBLE", line.trim()));
                    break;
                }
            }
        } catch (Exception ignored) { /* 跳过该规则 */ }

        // RM_RF
        try {
            for (String line : lines) {
                if (RM_RF_PATTERN.matcher(line).find()) {
                    addUnique(results, seen,
                            new RiskFinding("DESTRUCTIVE", "RM_RF",
                                    "BLOCK", "IRREVERSIBLE", line.trim()));
                    break;
                }
            }
        } catch (Exception ignored) { /* 跳过该规则 */ }

        // MIGRATION_TOUCH（WARN）: WRITE 操作改动已存在的迁移 SQL 文件
        try {
            if ("WRITE".equals(opType) && isMigrationFile(filePath)) {
                addUnique(results, seen,
                        new RiskFinding("DESTRUCTIVE", "MIGRATION_TOUCH",
                                "WARN", "REVERSIBLE", filePath));
            }
        } catch (Exception ignored) { /* 跳过该规则 */ }

        // MASS_SHRINK（WARN）: 新行数 < 旧行数 * 0.5，且旧行数 ≥ 20
        try {
            String old = oldContent != null ? oldContent : "";
            long oldLineCount = old.isEmpty() ? 0L : countLines(old);
            long newLineCount = content.isEmpty() ? 0L : countLines(content);
            if (oldLineCount >= 20 && newLineCount < oldLineCount * 0.5) {
                addUnique(results, seen,
                        new RiskFinding("DESTRUCTIVE", "MASS_SHRINK",
                                "WARN", "REVERSIBLE",
                                "old=" + oldLineCount + " new=" + newLineCount));
            }
        } catch (Exception ignored) { /* 跳过该规则 */ }

        return results;
    }

    // ── 内部工具 ──────────────────────────────────────────────────────────────

    /** 判断是否为迁移 SQL 文件：路径含 db/migration/ 或（含 migration 且 .sql 结尾）。 */
    private static boolean isMigrationFile(String filePath) {
        if (filePath == null) return false;
        String lower = filePath.toLowerCase();
        return lower.contains("db/migration/")
                || (lower.contains("migration") && lower.endsWith(".sql"));
    }

    /** 计算内容行数（以 \n 分割）。 */
    private static long countLines(String text) {
        return text.split("\n", -1).length;
    }

    /** 按 ruleCode 去重：同一规则只保留第一次触发。 */
    private static void addUnique(List<RiskFinding> results, Set<String> seen, RiskFinding finding) {
        if (seen.add(finding.ruleCode())) {
            results.add(finding);
        }
    }
}
