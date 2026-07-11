package com.repolens.service.impl.support;

import com.repolens.domain.entity.FileChangeLogEntity;
import com.repolens.domain.entity.ToolCallLogEntity;
import com.repolens.domain.vo.ReconciliationVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD tests for ConstraintChecker (Feature B P2).
 * Pure function — no Spring context, no mocks.
 */
class ConstraintCheckerTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static FileChangeLogEntity makeChange(String filePath) {
        FileChangeLogEntity c = new FileChangeLogEntity();
        c.setFilePath(filePath);
        c.setStatus(FileChangeLogEntity.STATUS_APPLIED);
        c.setOpType(FileChangeLogEntity.OP_TYPE_WRITE);
        return c;
    }

    private static FileChangeLogEntity makeChange(String filePath, String oldContent, String newContent) {
        FileChangeLogEntity c = makeChange(filePath);
        c.setOldContent(oldContent);
        c.setNewContent(newContent);
        return c;
    }

    private static ToolCallLogEntity makeVerification(int exitCode) {
        ToolCallLogEntity t = new ToolCallLogEntity();
        t.setToolName("runVerification");
        t.setOutputJson("{\"exitCode\":" + exitCode + "}");
        return t;
    }

    // ── PATH_FORBIDDEN ────────────────────────────────────────────────────────

    @Test
    void pathForbidden_matchesChangedFile() {
        ConstraintRule rule = new ConstraintRule(
                ConstraintRule.PATH_FORBIDDEN, "migration", "don't touch migration", true,
                ConstraintRule.SEVERITY_BLOCK);
        FileChangeLogEntity change = makeChange("src/main/resources/db/migration/V001.sql");

        List<ReconciliationVO.ConstraintViolation> violations =
                ConstraintChecker.checkViolations(List.of(rule), List.of(change), List.of());

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).getRuleType()).isEqualTo(ConstraintRule.PATH_FORBIDDEN);
        assertThat(violations.get(0).getMatchedFiles()).contains("src/main/resources/db/migration/V001.sql");
        assertThat(violations.get(0).getSeverity()).isEqualTo(ConstraintRule.SEVERITY_BLOCK);
    }

    @Test
    void pathForbidden_noMatchForSafeFile() {
        ConstraintRule rule = new ConstraintRule(
                ConstraintRule.PATH_FORBIDDEN, "src/legacy", "don't touch src/legacy", true,
                ConstraintRule.SEVERITY_BLOCK);
        FileChangeLogEntity change = makeChange("src/main/java/MyService.java");

        List<ReconciliationVO.ConstraintViolation> violations =
                ConstraintChecker.checkViolations(List.of(rule), List.of(change), List.of());

        assertThat(violations).isEmpty();
    }

    @Test
    void pathForbidden_matchesPathWithSlash() {
        ConstraintRule rule = new ConstraintRule(
                ConstraintRule.PATH_FORBIDDEN, "src/legacy/", "don't touch src/legacy", true,
                ConstraintRule.SEVERITY_BLOCK);
        FileChangeLogEntity change = makeChange("src/legacy/OldService.java");

        List<ReconciliationVO.ConstraintViolation> violations =
                ConstraintChecker.checkViolations(List.of(rule), List.of(change), List.of());

        assertThat(violations).hasSize(1);
    }

    // ── FILETYPE_FORBIDDEN ────────────────────────────────────────────────────

    @Test
    void filetypeForbidden_lockFile_triggers() {
        ConstraintRule rule = new ConstraintRule(
                ConstraintRule.FILETYPE_FORBIDDEN, "*.lock", "don't modify *.lock", true,
                ConstraintRule.SEVERITY_BLOCK);
        FileChangeLogEntity change = makeChange("yarn.lock");  // ends with .lock

        List<ReconciliationVO.ConstraintViolation> violations =
                ConstraintChecker.checkViolations(List.of(rule), List.of(change), List.of());

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).getRuleType()).isEqualTo(ConstraintRule.FILETYPE_FORBIDDEN);
    }

    @Test
    void filetypeForbidden_sqlFile_triggers() {
        ConstraintRule rule = new ConstraintRule(
                ConstraintRule.FILETYPE_FORBIDDEN, "*.sql", "don't touch *.sql", true,
                ConstraintRule.SEVERITY_BLOCK);
        FileChangeLogEntity change = makeChange("src/db/V20260706__schema.sql");

        List<ReconciliationVO.ConstraintViolation> violations =
                ConstraintChecker.checkViolations(List.of(rule), List.of(change), List.of());

        assertThat(violations).hasSize(1);
    }

    @Test
    void filetypeForbidden_noMatchForDifferentExtension() {
        ConstraintRule rule = new ConstraintRule(
                ConstraintRule.FILETYPE_FORBIDDEN, "*.lock", "don't modify *.lock", true,
                ConstraintRule.SEVERITY_BLOCK);
        FileChangeLogEntity change = makeChange("src/main/java/MyService.java");

        List<ReconciliationVO.ConstraintViolation> violations =
                ConstraintChecker.checkViolations(List.of(rule), List.of(change), List.of());

        assertThat(violations).isEmpty();
    }

    // ── NO_NEW_DEP ────────────────────────────────────────────────────────────

    @Test
    void noNewDep_pomXmlModified_triggers() {
        ConstraintRule rule = new ConstraintRule(
                ConstraintRule.NO_NEW_DEP, null, "don't add new dependencies", true,
                ConstraintRule.SEVERITY_BLOCK);
        String oldPom = "<dependencies><dependency><groupId>org.springframework</groupId></dependency></dependencies>";
        String newPom = oldPom + "<dependency><groupId>com.example</groupId><artifactId>new-lib</artifactId></dependency>";
        FileChangeLogEntity change = makeChange("pom.xml", oldPom, newPom);

        List<ReconciliationVO.ConstraintViolation> violations =
                ConstraintChecker.checkViolations(List.of(rule), List.of(change), List.of());

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).getRuleType()).isEqualTo(ConstraintRule.NO_NEW_DEP);
    }

    @Test
    void noNewDep_packageJsonModified_triggers() {
        ConstraintRule rule = new ConstraintRule(
                ConstraintRule.NO_NEW_DEP, null, "don't add dependencies", true,
                ConstraintRule.SEVERITY_BLOCK);
        String oldPkg = "{\"dependencies\":{\"react\":\"18.0.0\"}}";
        String newPkg = "{\"dependencies\":{\"react\":\"18.0.0\",\"lodash\":\"4.17.21\"}}";
        FileChangeLogEntity change = makeChange("package.json", oldPkg, newPkg);

        List<ReconciliationVO.ConstraintViolation> violations =
                ConstraintChecker.checkViolations(List.of(rule), List.of(change), List.of());

        assertThat(violations).hasSize(1);
    }

    @Test
    void noNewDep_javaFileOnly_noTrigger() {
        ConstraintRule rule = new ConstraintRule(
                ConstraintRule.NO_NEW_DEP, null, "don't add dependencies", true,
                ConstraintRule.SEVERITY_BLOCK);
        FileChangeLogEntity change = makeChange("src/main/java/Foo.java", "old", "new");

        List<ReconciliationVO.ConstraintViolation> violations =
                ConstraintChecker.checkViolations(List.of(rule), List.of(change), List.of());

        assertThat(violations).isEmpty();
    }

    // ── MUST_VERIFY ───────────────────────────────────────────────────────────

    @Test
    void mustVerify_noRunVerification_triggers() {
        ConstraintRule rule = new ConstraintRule(
                ConstraintRule.MUST_VERIFY, null, "always run tests", true,
                ConstraintRule.SEVERITY_BLOCK);
        FileChangeLogEntity change = makeChange("src/Foo.java");

        List<ReconciliationVO.ConstraintViolation> violations =
                ConstraintChecker.checkViolations(List.of(rule), List.of(change), List.of());

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).getRuleType()).isEqualTo(ConstraintRule.MUST_VERIFY);
        assertThat(violations.get(0).getMatchedFiles()).isEmpty();
    }

    @Test
    void mustVerify_withRunVerification_noViolation() {
        ConstraintRule rule = new ConstraintRule(
                ConstraintRule.MUST_VERIFY, null, "always run tests", true,
                ConstraintRule.SEVERITY_BLOCK);
        FileChangeLogEntity change = makeChange("src/Foo.java");

        List<ReconciliationVO.ConstraintViolation> violations =
                ConstraintChecker.checkViolations(
                        List.of(rule), List.of(change), List.of(makeVerification(0)));

        assertThat(violations).isEmpty();
    }

    // ── SEMANTIC — never produces violations ──────────────────────────────────

    @Test
    void semanticRule_neverProducesViolation() {
        ConstraintRule rule = new ConstraintRule(
                ConstraintRule.SEMANTIC, null, "should use camelCase", false,
                ConstraintRule.SEVERITY_WARN);
        FileChangeLogEntity change = makeChange("src/main/java/SnakeCase_file.java");

        List<ReconciliationVO.ConstraintViolation> violations =
                ConstraintChecker.checkViolations(List.of(rule), List.of(change), List.of());

        assertThat(violations).isEmpty();
    }

    // ── Failure safety ────────────────────────────────────────────────────────

    @Test
    void emptyRules_returnsEmpty() {
        assertThat(ConstraintChecker.checkViolations(
                List.of(),
                List.of(makeChange("src/Foo.java")),
                List.of())).isEmpty();
    }

    @Test
    void mustVerify_noChanges_noViolation() {
        // If there are no changes at all, MUST_VERIFY shouldn't trigger (nothing to verify)
        ConstraintRule rule = new ConstraintRule(
                ConstraintRule.MUST_VERIFY, null, "always run tests", true,
                ConstraintRule.SEVERITY_BLOCK);

        List<ReconciliationVO.ConstraintViolation> violations =
                ConstraintChecker.checkViolations(List.of(rule), List.of(), List.of());

        // With no changes, there's nothing to verify — no violation
        assertThat(violations).isEmpty();
    }

    // ── matchesPathPattern (package-visible for test) ─────────────────────────

    @Test
    void matchesPathPattern_directorySegment() {
        assertThat(ConstraintChecker.matchesPathPattern("src/main/resources/db/migration/V001.sql", "migration")).isTrue();
    }

    @Test
    void matchesPathPattern_pathPrefix() {
        assertThat(ConstraintChecker.matchesPathPattern("src/legacy/Foo.java", "src/legacy")).isTrue();
    }

    @Test
    void matchesPathPattern_noMatch() {
        assertThat(ConstraintChecker.matchesPathPattern("src/main/java/Foo.java", "src/legacy")).isFalse();
    }

    // ── matchesGlobPattern ────────────────────────────────────────────────────

    @Test
    void matchesGlobPattern_lockFile() {
        assertThat(ConstraintChecker.matchesGlobPattern("package-lock.json", "*.lock")).isFalse(); // .json not .lock
        assertThat(ConstraintChecker.matchesGlobPattern("yarn.lock", "*.lock")).isTrue();
    }

    @Test
    void matchesGlobPattern_sqlFile() {
        assertThat(ConstraintChecker.matchesGlobPattern("V001__schema.sql", "*.sql")).isTrue();
    }

    // ── isDependencyManifest ──────────────────────────────────────────────────

    @Test
    void isDependencyManifest_recognizesPomXml() {
        assertThat(ConstraintChecker.isDependencyManifest("pom.xml")).isTrue();
        assertThat(ConstraintChecker.isDependencyManifest("src/main/pom.xml")).isTrue();
    }

    @Test
    void isDependencyManifest_recognizesPackageJson() {
        assertThat(ConstraintChecker.isDependencyManifest("package.json")).isTrue();
    }

    @Test
    void isDependencyManifest_javaFileIsFalse() {
        assertThat(ConstraintChecker.isDependencyManifest("src/Foo.java")).isFalse();
    }
}
