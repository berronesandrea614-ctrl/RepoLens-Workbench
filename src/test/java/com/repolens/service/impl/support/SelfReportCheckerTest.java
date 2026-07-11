package com.repolens.service.impl.support;

import com.repolens.domain.entity.FileChangeLogEntity;
import com.repolens.domain.entity.ToolCallLogEntity;
import com.repolens.domain.vo.ReconciliationVO.SelfReportCheck;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自报核对检查单测（Feature B P1）。
 * 覆盖：FABRICATED_VERIFICATION / CLAIM_CONTRADICTS_RESULT / TEST_WEAKENED / NO_OP_SUCCESS。
 * 全确定性，无外部依赖。
 */
class SelfReportCheckerTest {

    // ── 1. FABRICATED_VERIFICATION ─────────────────────────────────────────

    @Test
    void check1_FABRICATED_VERIFICATION_whenClaimedVerifiedAndNoRunVerification() {
        // Claimed verified, but no runVerification in tool_call_log
        List<SelfReportCheck> checks = SelfReportChecker.runChecks(
                false, true, List.of(), List.of());
        assertThat(checks).anyMatch(c -> "FABRICATED_VERIFICATION".equals(c.getType())
                && "RED".equals(c.getSeverity()));
    }

    @Test
    void check1_notFired_whenClaimedVerifiedAndHasRunVerification() {
        List<ToolCallLogEntity> logs = List.of(makeVerificationLog("{\"exitCode\":0}"));
        List<SelfReportCheck> checks = SelfReportChecker.runChecks(
                false, true, logs, List.of());
        assertThat(checks).noneMatch(c -> "FABRICATED_VERIFICATION".equals(c.getType()));
    }

    @Test
    void check1_notFired_whenNotClaimedVerified() {
        List<SelfReportCheck> checks = SelfReportChecker.runChecks(
                false, false, List.of(), List.of());
        assertThat(checks).noneMatch(c -> "FABRICATED_VERIFICATION".equals(c.getType()));
    }

    // ── 2. CLAIM_CONTRADICTS_RESULT ────────────────────────────────────────

    @Test
    void check2_CLAIM_CONTRADICTS_RESULT_whenExitCodeNonZeroAndClaimedSuccess() {
        List<ToolCallLogEntity> logs = List.of(makeVerificationLog("{\"exitCode\":1}"));
        List<SelfReportCheck> checks = SelfReportChecker.runChecks(
                true, false, logs, List.of());
        assertThat(checks).anyMatch(c -> "CLAIM_CONTRADICTS_RESULT".equals(c.getType())
                && "RED".equals(c.getSeverity()));
    }

    @Test
    void check2_CLAIM_CONTRADICTS_RESULT_whenTimedOutAndClaimedVerified() {
        List<ToolCallLogEntity> logs = List.of(makeVerificationLog("{\"timedOut\":true}"));
        List<SelfReportCheck> checks = SelfReportChecker.runChecks(
                false, true, logs, List.of());
        assertThat(checks).anyMatch(c -> "CLAIM_CONTRADICTS_RESULT".equals(c.getType()));
    }

    @Test
    void check2_notFired_whenExitCodeZeroAndClaimedSuccess() {
        List<ToolCallLogEntity> logs = List.of(makeVerificationLog("{\"exitCode\":0}"));
        List<SelfReportCheck> checks = SelfReportChecker.runChecks(
                true, false, logs, List.of());
        assertThat(checks).noneMatch(c -> "CLAIM_CONTRADICTS_RESULT".equals(c.getType()));
    }

    // ── 3. TEST_WEAKENED ───────────────────────────────────────────────────

    @Test
    void check3_TEST_WEAKENED_whenAssertionNetDecrease() {
        // Old code: 3 line-level assertion markers, new code: 2 (one removed)
        String oldCode = "@Test\n"
                       + "void t() {\n"
                       + "  assertEquals(1, x);\n"
                       + "  assertTrue(ok);\n"
                       + "}";
        String newCode = "@Test\n"
                       + "void t() {\n"
                       + "  assertEquals(1, x);\n"
                       + "}";
        FileChangeLogEntity change = makeTestChange(
                "src/test/java/FooTest.java", oldCode, newCode);
        List<SelfReportCheck> checks = SelfReportChecker.runChecks(
                false, false, List.of(), List.of(change));
        assertThat(checks).anyMatch(c -> "TEST_WEAKENED".equals(c.getType())
                && "RED".equals(c.getSeverity()));
    }

    @Test
    void check3_TEST_WEAKENED_whenNewDisabled() {
        String oldCode = "@Test\nvoid t() { assertEquals(1, x); }";
        String newCode = "@Disabled\n@Test\nvoid t() { assertEquals(1, x); }";
        FileChangeLogEntity change = makeTestChange(
                "src/test/java/FooTest.java", oldCode, newCode);
        List<SelfReportCheck> checks = SelfReportChecker.runChecks(
                false, false, List.of(), List.of(change));
        assertThat(checks).anyMatch(c -> "TEST_WEAKENED".equals(c.getType()));
    }

    @Test
    void check3_notFired_whenNonTestFile() {
        String oldCode = "assertEquals(1, x);";
        String newCode = "// removed assertion";
        FileChangeLogEntity change = makeTestChange(
                "src/main/java/FooService.java", oldCode, newCode);  // NOT a test file
        List<SelfReportCheck> checks = SelfReportChecker.runChecks(
                false, false, List.of(), List.of(change));
        assertThat(checks).noneMatch(c -> "TEST_WEAKENED".equals(c.getType()));
    }

    @Test
    void check3_notFired_whenAssertionCountIncreases() {
        String oldCode = "@Test\nvoid t() { assertEquals(1, x); }";
        String newCode = "@Test\nvoid t() { assertEquals(1, x); assertTrue(ok); }";
        FileChangeLogEntity change = makeTestChange(
                "src/test/java/FooTest.java", oldCode, newCode);
        List<SelfReportCheck> checks = SelfReportChecker.runChecks(
                false, false, List.of(), List.of(change));
        assertThat(checks).noneMatch(c -> "TEST_WEAKENED".equals(c.getType()));
    }

    // ── 4. NO_OP_SUCCESS ──────────────────────────────────────────────────

    @Test
    void check4_NO_OP_SUCCESS_whenClaimedSuccessAndNoEffectiveChanges() {
        List<SelfReportCheck> checks = SelfReportChecker.runChecks(
                true, false, List.of(), List.of());  // empty changes
        assertThat(checks).anyMatch(c -> "NO_OP_SUCCESS".equals(c.getType())
                && "ORANGE".equals(c.getSeverity()));
    }

    @Test
    void check4_NO_OP_SUCCESS_whenAllChangesRejected() {
        FileChangeLogEntity change = new FileChangeLogEntity();
        change.setFilePath("src/Foo.java");
        change.setStatus(FileChangeLogEntity.STATUS_REJECTED);
        List<SelfReportCheck> checks = SelfReportChecker.runChecks(
                true, false, List.of(), List.of(change));
        assertThat(checks).anyMatch(c -> "NO_OP_SUCCESS".equals(c.getType()));
    }

    @Test
    void check4_notFired_whenEffectiveChangesExist() {
        FileChangeLogEntity change = new FileChangeLogEntity();
        change.setFilePath("src/Foo.java");
        change.setStatus(FileChangeLogEntity.STATUS_APPLIED);
        List<SelfReportCheck> checks = SelfReportChecker.runChecks(
                true, false, List.of(), List.of(change));
        assertThat(checks).noneMatch(c -> "NO_OP_SUCCESS".equals(c.getType()));
    }

    // ── Trust Flag ─────────────────────────────────────────────────────────

    @Test
    void trustFlag_FABRICATED_whenAnyRedCheck() {
        List<SelfReportCheck> checks = List.of(
                SelfReportCheck.builder().type("FABRICATED_VERIFICATION").severity("RED").detail("x").build());
        assertThat(SelfReportChecker.computeTrustFlag(checks)).isEqualTo("FABRICATED");
    }

    @Test
    void trustFlag_SUSPECT_whenOnlyOrangeCheck() {
        List<SelfReportCheck> checks = List.of(
                SelfReportCheck.builder().type("NO_OP_SUCCESS").severity("ORANGE").detail("x").build());
        assertThat(SelfReportChecker.computeTrustFlag(checks)).isEqualTo("SUSPECT");
    }

    @Test
    void trustFlag_OK_whenNoChecks() {
        assertThat(SelfReportChecker.computeTrustFlag(List.of())).isEqualTo("OK");
    }

    // ── staleVerification ─────────────────────────────────────────────────

    @Test
    void isStaleVerification_trueWhenHasRunVerification() {
        assertThat(SelfReportChecker.isStaleVerification(
                List.of(makeVerificationLog("{\"exitCode\":0}")))).isTrue();
    }

    @Test
    void isStaleVerification_falseWhenNoRunVerification() {
        ToolCallLogEntity log = new ToolCallLogEntity();
        log.setToolName("readFile");
        assertThat(SelfReportChecker.isStaleVerification(List.of(log))).isFalse();
    }

    // ── Acceptance Test Scenario ──────────────────────────────────────────

    /**
     * 验收场景：声明改3文件，最终答案说"已测试通过"但没调 runVerification，
     * 且改了测试文件并删了1处断言 → FABRICATED_VERIFICATION + TEST_WEAKENED + trustFlag=FABRICATED。
     */
    @Test
    void acceptance_fabricatedVerification_andTestWeakened_givesFabricatedTrust() {
        // 答案声称已测试通过
        boolean claimedVerified = true;
        boolean claimedSuccess = true;

        // 无 runVerification 记录
        List<ToolCallLogEntity> toolCallLogs = List.of();

        // 改了测试文件，删了1处断言（每行一个 assert，确保计数不重叠）
        String oldCode = "@Test\n"
                       + "void t1() {\n"
                       + "  assertEquals(1, r);\n"
                       + "  assertTrue(ok);\n"
                       + "}";
        String newCode = "@Test\n"
                       + "void t1() {\n"
                       + "  assertEquals(1, r);\n"
                       + "}";
        FileChangeLogEntity testChange = makeTestChange(
                "src/test/java/VerifyCodeServiceTest.java", oldCode, newCode);

        List<SelfReportCheck> checks = SelfReportChecker.runChecks(
                claimedSuccess, claimedVerified, toolCallLogs, List.of(testChange));

        assertThat(checks).anyMatch(c -> "FABRICATED_VERIFICATION".equals(c.getType()));
        assertThat(checks).anyMatch(c -> "TEST_WEAKENED".equals(c.getType()));
        assertThat(SelfReportChecker.computeTrustFlag(checks)).isEqualTo("FABRICATED");
    }

    // ── 辅助构造 ─────────────────────────────────────────────────────────

    private ToolCallLogEntity makeVerificationLog(String outputJson) {
        ToolCallLogEntity log = new ToolCallLogEntity();
        log.setToolName("runVerification");
        log.setOutputJson(outputJson);
        return log;
    }

    private FileChangeLogEntity makeTestChange(String filePath, String oldContent, String newContent) {
        FileChangeLogEntity change = new FileChangeLogEntity();
        change.setFilePath(filePath);
        change.setOldContent(oldContent);
        change.setNewContent(newContent);
        change.setStatus(FileChangeLogEntity.STATUS_APPLIED);
        change.setOpType(FileChangeLogEntity.OP_TYPE_WRITE);
        return change;
    }
}
