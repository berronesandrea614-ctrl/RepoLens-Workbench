package com.repolens.service.impl.support;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses AGENTS.md / CLAUDE.md rule text into typed {@link ConstraintRule} objects
 * using deterministic regex (Feature B P2).
 *
 * <p>Strategy: split rulesText by newline; for each non-blank, non-header line,
 * try rule-type patterns in priority order; first match wins.
 * Lines that match none of the checkable patterns may become SEMANTIC (advisory)
 * if they contain a soft-constraint keyword.
 * Failure-safe: any per-line exception is silently skipped.
 */
public final class ConstraintRuleParser {

    // ── PATH_FORBIDDEN patterns ────────────────────────────────────────────────
    // Chinese: "不要改 migration 目录", "禁止修改 src/db/"
    private static final Pattern PATH_ZH = Pattern.compile(
            "(?:不要|禁止|不得|不可|勿|避免)\\s*(?:改|修改|编辑|碰|动|更改)\\s+" +
            "([a-zA-Z0-9_.\\-]+(?:/[a-zA-Z0-9_.\\-]+)*/?)(?:\\s*(?:目录|文件夹|目|folder|dir))?",
            Pattern.CASE_INSENSITIVE
    );

    // English: "don't touch src/legacy", "do not modify the config/ folder"
    private static final Pattern PATH_EN = Pattern.compile(
            "(?:don'?t|do\\s+not|never|avoid)\\s+(?:touch|modify|change|edit|update)\\s+" +
            "(?:the\\s+)?([a-zA-Z0-9_.\\-]+(?:/[a-zA-Z0-9_.\\-]+)*/?)",
            Pattern.CASE_INSENSITIVE
    );

    // "src/legacy/ is off limits" / "config/ is forbidden"
    private static final Pattern PATH_OFFIMITS = Pattern.compile(
            "([a-zA-Z0-9_.\\-]+(?:/[a-zA-Z0-9_.\\-]+)*/?)\\s+(?:is|are)\\s+" +
            "(?:off.?limits|forbidden|restricted|read.?only)",
            Pattern.CASE_INSENSITIVE
    );

    // ── FILETYPE_FORBIDDEN patterns ─────────────────────────────────────────────
    // "don't modify *.lock", "禁止修改 *.sql 迁移文件"
    private static final Pattern FILETYPE_PATTERN = Pattern.compile(
            "(?:不要|禁止|不得|don'?t|do not|never|avoid)\\s*" +
            "(?:改|修改|碰|动|touch|modify|change|commit|add|edit)\\s+" +
            "[^\\*]*(\\*\\.[a-zA-Z0-9]+)",
            Pattern.CASE_INSENSITIVE
    );

    // ── NO_NEW_DEP patterns ─────────────────────────────────────────────────────
    // "don't add new dependencies", "no new deps", "禁止新增依赖库"
    private static final Pattern NO_NEW_DEP_PATTERN = Pattern.compile(
            "(?:不要|禁止|不得|no|don'?t|do\\s+not|avoid|never)\\s*" +
            ".*?(?:新增|添加|add|adding|introduce|introducing)\\s*" +
            ".*?(?:依赖|dependency|dependencies|dep|deps|package|packages|lib|library)",
            Pattern.CASE_INSENSITIVE
    );

    // ── MUST_VERIFY patterns ───────────────────────────────────────────────────
    // "always run tests before completing", "必须跑测试"
    private static final Pattern MUST_VERIFY_PATTERN = Pattern.compile(
            "(?:必须|always|must|need to|needs to|required to|should always)\\s*" +
            ".*?(?:跑|运行|执行|run|pass|complete|do)\\s*" +
            ".*?(?:测试|test|tests|verification|verify|check|checks)",
            Pattern.CASE_INSENSITIVE
    );

    // ── KEEP_SCOPE patterns ────────────────────────────────────────────────────
    // "stay in scope", "只能改 X", "仅限于 service 层"
    private static final Pattern KEEP_SCOPE_PATTERN = Pattern.compile(
            "(?:只能|仅限|stay in|keep within|remain in|only modify|only change)\\s*" +
            ".*?(?:改|修改|scope|within|layer|module|package)",
            Pattern.CASE_INSENSITIVE
    );

    // ── SEMANTIC hint (advisory) ───────────────────────────────────────────────
    // Lines containing soft constraint keywords — not checkable, advisory only
    private static final Pattern SEMANTIC_HINT = Pattern.compile(
            "\\b(?:should|must|always|never|prefer|avoid|ensure|make sure|" +
            "use|follow|maintain|keep|apply|write|add)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private ConstraintRuleParser() {}

    /**
     * Parses rules text into a list of {@link ConstraintRule} objects.
     * Returns an empty list for null/blank input.
     * Individual line failures are silently skipped (failure-safe).
     *
     * @param rulesText raw text from AGENTS.md / CLAUDE.md
     * @return immutable list of parsed rules
     */
    public static List<ConstraintRule> parseRules(String rulesText) {
        if (rulesText == null || rulesText.isBlank()) {
            return List.of();
        }

        List<ConstraintRule> result = new ArrayList<>();
        for (String raw : rulesText.split("\n")) {
            try {
                ConstraintRule rule = classifyLine(raw);
                if (rule != null) result.add(rule);
            } catch (Exception ex) {
                // silently skip malformed lines
            }
        }
        return List.copyOf(result);
    }

    // ── Classification logic ──────────────────────────────────────────────────

    private static ConstraintRule classifyLine(String raw) {
        if (raw == null) return null;
        // Strip markdown list markers and leading/trailing whitespace
        String line = raw.trim().replaceFirst("^[-*+>]\\s+", "").trim();
        if (line.isEmpty()) return null;
        // Skip markdown headers
        if (line.startsWith("#")) return null;

        // ── PATH_FORBIDDEN ────────────────────────────────────────────────────
        {
            Matcher m = PATH_ZH.matcher(line);
            if (m.find()) {
                return new ConstraintRule(ConstraintRule.PATH_FORBIDDEN,
                        m.group(1), line, true, ConstraintRule.SEVERITY_BLOCK);
            }
        }
        {
            Matcher m = PATH_EN.matcher(line);
            if (m.find()) {
                return new ConstraintRule(ConstraintRule.PATH_FORBIDDEN,
                        m.group(1), line, true, ConstraintRule.SEVERITY_BLOCK);
            }
        }
        {
            Matcher m = PATH_OFFIMITS.matcher(line);
            if (m.find()) {
                return new ConstraintRule(ConstraintRule.PATH_FORBIDDEN,
                        m.group(1), line, true, ConstraintRule.SEVERITY_BLOCK);
            }
        }

        // ── FILETYPE_FORBIDDEN ────────────────────────────────────────────────
        {
            Matcher m = FILETYPE_PATTERN.matcher(line);
            if (m.find()) {
                return new ConstraintRule(ConstraintRule.FILETYPE_FORBIDDEN,
                        m.group(1), line, true, ConstraintRule.SEVERITY_BLOCK);
            }
        }

        // ── NO_NEW_DEP ────────────────────────────────────────────────────────
        if (NO_NEW_DEP_PATTERN.matcher(line).find()) {
            return new ConstraintRule(ConstraintRule.NO_NEW_DEP,
                    null, line, true, ConstraintRule.SEVERITY_BLOCK);
        }

        // ── MUST_VERIFY ───────────────────────────────────────────────────────
        if (MUST_VERIFY_PATTERN.matcher(line).find()) {
            return new ConstraintRule(ConstraintRule.MUST_VERIFY,
                    null, line, true, ConstraintRule.SEVERITY_BLOCK);
        }

        // ── KEEP_SCOPE ────────────────────────────────────────────────────────
        if (KEEP_SCOPE_PATTERN.matcher(line).find()) {
            return new ConstraintRule(ConstraintRule.KEEP_SCOPE,
                    null, line, false, ConstraintRule.SEVERITY_WARN);
        }

        // ── SEMANTIC (advisory fallback) ──────────────────────────────────────
        if (SEMANTIC_HINT.matcher(line).find()) {
            return new ConstraintRule(ConstraintRule.SEMANTIC,
                    null, line, false, ConstraintRule.SEVERITY_WARN);
        }

        return null; // non-constraint line (e.g., descriptive prose)
    }
}
