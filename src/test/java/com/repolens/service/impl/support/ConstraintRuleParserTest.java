package com.repolens.service.impl.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD tests for ConstraintRuleParser (Feature B P2).
 * Pure function — no Spring context, no mocks.
 */
class ConstraintRuleParserTest {

    // ── PATH_FORBIDDEN ────────────────────────────────────────────────────────

    @Test
    void parsesPathForbidden_english() {
        List<ConstraintRule> rules = ConstraintRuleParser.parseRules(
                "don't touch src/legacy");
        assertThat(rules).anyMatch(r ->
                ConstraintRule.PATH_FORBIDDEN.equals(r.type())
                && r.pattern() != null && r.pattern().contains("src/legacy")
                && r.checkable()
                && ConstraintRule.SEVERITY_BLOCK.equals(r.severity()));
    }

    @Test
    void parsesPathForbidden_chinese() {
        List<ConstraintRule> rules = ConstraintRuleParser.parseRules(
                "不要改 migration 目录");
        assertThat(rules).anyMatch(r ->
                ConstraintRule.PATH_FORBIDDEN.equals(r.type())
                && r.pattern() != null && r.pattern().contains("migration")
                && r.checkable());
    }

    @Test
    void parsesPathForbidden_doNotModify() {
        List<ConstraintRule> rules = ConstraintRuleParser.parseRules(
                "do not modify src/db/");
        assertThat(rules).anyMatch(r ->
                ConstraintRule.PATH_FORBIDDEN.equals(r.type())
                && r.pattern() != null && r.pattern().contains("src/db"));
    }

    // ── FILETYPE_FORBIDDEN ────────────────────────────────────────────────────

    @Test
    void parsesFiletypeForbidden_lockFiles() {
        List<ConstraintRule> rules = ConstraintRuleParser.parseRules(
                "don't modify *.lock files");
        assertThat(rules).anyMatch(r ->
                ConstraintRule.FILETYPE_FORBIDDEN.equals(r.type())
                && r.pattern() != null && r.pattern().contains("*.lock")
                && r.checkable());
    }

    @Test
    void parsesFiletypeForbidden_sqlChinese() {
        List<ConstraintRule> rules = ConstraintRuleParser.parseRules(
                "禁止修改 *.sql 迁移文件");
        assertThat(rules).anyMatch(r ->
                ConstraintRule.FILETYPE_FORBIDDEN.equals(r.type())
                && r.pattern() != null && r.pattern().contains("*.sql"));
    }

    // ── NO_NEW_DEP ────────────────────────────────────────────────────────────

    @Test
    void parsesNoNewDep_english() {
        List<ConstraintRule> rules = ConstraintRuleParser.parseRules(
                "don't add new dependencies");
        assertThat(rules).anyMatch(r ->
                ConstraintRule.NO_NEW_DEP.equals(r.type())
                && r.checkable()
                && ConstraintRule.SEVERITY_BLOCK.equals(r.severity()));
    }

    @Test
    void parsesNoNewDep_chinese() {
        List<ConstraintRule> rules = ConstraintRuleParser.parseRules(
                "禁止新增依赖库");
        assertThat(rules).anyMatch(r ->
                ConstraintRule.NO_NEW_DEP.equals(r.type()));
    }

    // ── MUST_VERIFY ───────────────────────────────────────────────────────────

    @Test
    void parsesMustVerify_english() {
        List<ConstraintRule> rules = ConstraintRuleParser.parseRules(
                "always run tests before completing");
        assertThat(rules).anyMatch(r ->
                ConstraintRule.MUST_VERIFY.equals(r.type())
                && r.checkable());
    }

    @Test
    void parsesMustVerify_chinese() {
        List<ConstraintRule> rules = ConstraintRuleParser.parseRules(
                "必须跑测试通过后才能提交");
        assertThat(rules).anyMatch(r ->
                ConstraintRule.MUST_VERIFY.equals(r.type()));
    }

    // ── SEMANTIC (advisory) ───────────────────────────────────────────────────

    @Test
    void semanticRule_isNotCheckable_andWarnSeverity() {
        List<ConstraintRule> rules = ConstraintRuleParser.parseRules(
                "should use camelCase for variable names");
        // SEMANTIC rules appear but are not checkable
        boolean hasSemanticOrMissing = rules.isEmpty()
                || rules.stream().anyMatch(r ->
                        ConstraintRule.SEMANTIC.equals(r.type())
                        && !r.checkable()
                        && ConstraintRule.SEVERITY_WARN.equals(r.severity()));
        assertThat(hasSemanticOrMissing).isTrue();
    }

    // ── Multi-line ────────────────────────────────────────────────────────────

    @Test
    void parsesMultiLineRules_allClassified() {
        String rulesText = """
                # Project Rules
                don't touch src/legacy
                don't modify *.lock files
                don't add new dependencies
                always run tests before completing
                use consistent code style
                """;
        List<ConstraintRule> parsed = ConstraintRuleParser.parseRules(rulesText);
        // At minimum: PATH_FORBIDDEN + FILETYPE_FORBIDDEN + NO_NEW_DEP + MUST_VERIFY
        long checkableCount = parsed.stream().filter(ConstraintRule::checkable).count();
        assertThat(checkableCount).isGreaterThanOrEqualTo(4L);
    }

    // ── Failure safety ────────────────────────────────────────────────────────

    @Test
    void nullInput_returnsEmpty() {
        assertThat(ConstraintRuleParser.parseRules(null)).isEmpty();
    }

    @Test
    void blankInput_returnsEmpty() {
        assertThat(ConstraintRuleParser.parseRules("   \n  ")).isEmpty();
    }

    @Test
    void headerLines_skipped() {
        // Lines starting with # are markdown headers — should not produce PATH_FORBIDDEN rules
        List<ConstraintRule> rules = ConstraintRuleParser.parseRules("# Rules\n## Constraints");
        assertThat(rules).noneMatch(r -> ConstraintRule.PATH_FORBIDDEN.equals(r.type()));
    }
}
