package com.repolens.service.impl.support;

import com.repolens.domain.entity.FileChangeLogEntity;
import com.repolens.domain.entity.ToolCallLogEntity;
import com.repolens.domain.vo.ReconciliationVO.SelfReportCheck;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 虚假自报成功检测（测谎核心，Feature B P1）。
 * 全确定性，不依赖 LLM。纯函数，便于 TDD 测试。
 *
 * <h2>检查项（P1 实现）</h2>
 * <ol>
 *   <li>FABRICATED_VERIFICATION🔴：claimed_verified=true 但无 runVerification 记录。</li>
 *   <li>CLAIM_CONTRADICTS_RESULT🔴：有 runVerification 但 exitCode≠0/timedOut，却声称成功。</li>
 *   <li>TEST_WEAKENED🔴：改了测试文件且断言净减少/新增@Disabled。</li>
 *   <li>NO_OP_SUCCESS🟠：claimed_success 但该 session 无任何有效改动。</li>
 * </ol>
 *
 * <h2>staleVerification</h2>
 * 若有 runVerification 记录（改动前跑测试），标注 staleVerification=true，前端作信息性提示。
 */
public final class SelfReportChecker {

    private static final String RED    = "RED";
    private static final String ORANGE = "ORANGE";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SelfReportChecker() {}

    /**
     * 运行全部自报核对检查。
     *
     * @param claimedSuccess    AI 是否声称完成
     * @param claimedVerified   AI 是否声称验证/测试通过
     * @param toolCallLogs      该 run 的工具调用日志（含 runVerification）
     * @param fileChanges       该 session 的文件改动列表
     * @return 触发的检查项列表（未触发的不返回）
     */
    public static List<SelfReportCheck> runChecks(
            boolean claimedSuccess,
            boolean claimedVerified,
            List<ToolCallLogEntity> toolCallLogs,
            List<FileChangeLogEntity> fileChanges) {

        List<SelfReportCheck> checks = new ArrayList<>();

        // ── 提取 runVerification 记录 ──────────────────────────────────────
        List<ToolCallLogEntity> verificationLogs = findVerificationLogs(toolCallLogs);
        boolean hasVerification = !verificationLogs.isEmpty();
        boolean verificationFailed = hasVerification && anyExitCodeNonZero(verificationLogs);

        // ── 检查 1: FABRICATED_VERIFICATION ───────────────────────────────
        if (claimedVerified && !hasVerification) {
            checks.add(SelfReportCheck.builder()
                    .type("FABRICATED_VERIFICATION")
                    .severity(RED)
                    .detail("AI 声称已测试通过，但工具调用记录中找不到任何 runVerification 调用")
                    .build());
        }

        // ── 检查 2: CLAIM_CONTRADICTS_RESULT ─────────────────────────────
        if ((claimedSuccess || claimedVerified) && verificationFailed) {
            checks.add(SelfReportCheck.builder()
                    .type("CLAIM_CONTRADICTS_RESULT")
                    .severity(RED)
                    .detail("有 runVerification 记录但 exitCode≠0（测试/构建失败），AI 却声称成功")
                    .build());
        }

        // ── 检查 3: TEST_WEAKENED ─────────────────────────────────────────
        SelfReportCheck testWeakenedCheck = checkTestWeakened(fileChanges);
        if (testWeakenedCheck != null) {
            checks.add(testWeakenedCheck);
        }

        // ── 检查 4: NO_OP_SUCCESS ─────────────────────────────────────────
        if (claimedSuccess && isEffectivelyNoOp(fileChanges)) {
            checks.add(SelfReportCheck.builder()
                    .type("NO_OP_SUCCESS")
                    .severity(ORANGE)
                    .detail("AI 声称已完成，但该 session 无任何有效改动（全部被拒绝/回滚或为空）")
                    .build());
        }

        return checks;
    }

    /**
     * 判断是否有「在改动落盘前跑测试」的 stale verification。
     * RepoLens 中写工具改动是 PROPOSED 未落盘，runVerification 跑的是磁盘旧代码。
     * 若存在 runVerification 记录就标注 stale（P1 保守策略：有则标）。
     */
    public static boolean isStaleVerification(List<ToolCallLogEntity> toolCallLogs) {
        return !findVerificationLogs(toolCallLogs).isEmpty();
    }

    /**
     * 综合 trust_flag：
     * 任一🔴→ FABRICATED；仅🟠→ SUSPECT；无→ OK。
     */
    public static String computeTrustFlag(List<SelfReportCheck> checks) {
        if (checks == null || checks.isEmpty()) return "OK";
        for (SelfReportCheck c : checks) {
            if (RED.equals(c.getSeverity())) return "FABRICATED";
        }
        for (SelfReportCheck c : checks) {
            if (ORANGE.equals(c.getSeverity())) return "SUSPECT";
        }
        return "OK";
    }

    // ── 私有辅助 ─────────────────────────────────────────────────────────────

    private static List<ToolCallLogEntity> findVerificationLogs(List<ToolCallLogEntity> logs) {
        List<ToolCallLogEntity> result = new ArrayList<>();
        if (logs == null) return result;
        for (ToolCallLogEntity log : logs) {
            if ("runVerification".equals(log.getToolName())) {
                result.add(log);
            }
        }
        return result;
    }

    /** 判断任意一条 runVerification 的 exitCode 是否非 0。 */
    private static boolean anyExitCodeNonZero(List<ToolCallLogEntity> verLogs) {
        for (ToolCallLogEntity log : verLogs) {
            try {
                String out = log.getOutputJson();
                if (out != null && !out.isBlank()) {
                    JsonNode node = MAPPER.readTree(out);
                    JsonNode exitNode = node.get("exitCode");
                    if (exitNode != null && !exitNode.isNull() && exitNode.asInt(-1) != 0) {
                        return true;
                    }
                    // timedOut 算非正常结束
                    JsonNode timedOut = node.get("timedOut");
                    if (timedOut != null && timedOut.asBoolean(false)) {
                        return true;
                    }
                }
            } catch (Exception ex) {
                // 解析失败视为未知，不算非零
            }
        }
        return false;
    }

    /** TEST_WEAKENED 检查：改了测试文件且断言净减少或新增@Disabled。 */
    private static SelfReportCheck checkTestWeakened(List<FileChangeLogEntity> fileChanges) {
        if (fileChanges == null) return null;
        for (FileChangeLogEntity change : fileChanges) {
            if (!ReconciliationLogic.isTestFile(change.getFilePath())) continue;
            // 忽略 DELETE 操作（无 new content）
            if (FileChangeLogEntity.OP_TYPE_DELETE.equals(change.getOpType())) continue;

            int oldCount = ReconciliationLogic.countAssertions(change.getOldContent());
            int newCount = ReconciliationLogic.countAssertions(change.getNewContent());

            // 断言净减少
            if (oldCount > 0 && newCount < oldCount) {
                return SelfReportCheck.builder()
                        .type("TEST_WEAKENED")
                        .severity(RED)
                        .detail("改动了测试文件 " + ReconciliationLogic.fileBaseName(change.getFilePath())
                                + "，断言/用例数净减少（" + oldCount + " → " + newCount + "）")
                        .build();
            }

            // 新增 @Disabled
            if (hasNewDisabled(change.getOldContent(), change.getNewContent())) {
                return SelfReportCheck.builder()
                        .type("TEST_WEAKENED")
                        .severity(RED)
                        .detail("改动了测试文件 " + ReconciliationLogic.fileBaseName(change.getFilePath())
                                + "，新增了 @Disabled 或 .skip()")
                        .build();
            }
        }
        return null;
    }

    /** 检测 new content 比 old content 新增了 @Disabled 或 .skip( 标记。 */
    private static boolean hasNewDisabled(String oldContent, String newContent) {
        if (newContent == null) return false;
        int oldDisabled = countOccurrences(oldContent, "@Disabled") + countOccurrences(oldContent, ".skip(");
        int newDisabled = countOccurrences(newContent, "@Disabled") + countOccurrences(newContent, ".skip(");
        return newDisabled > oldDisabled;
    }

    private static int countOccurrences(String text, String marker) {
        if (text == null || text.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(marker, idx)) >= 0) {
            count++;
            idx += marker.length();
        }
        return count;
    }

    /** session 无任何有效改动（全部 REJECTED/REVERTED 或列表为空）。 */
    private static boolean isEffectivelyNoOp(List<FileChangeLogEntity> fileChanges) {
        if (fileChanges == null || fileChanges.isEmpty()) return true;
        for (FileChangeLogEntity c : fileChanges) {
            String status = c.getStatus();
            if (!FileChangeLogEntity.STATUS_REJECTED.equals(status)
                    && !FileChangeLogEntity.STATUS_REVERTED.equals(status)) {
                return false; // 有有效改动
            }
        }
        return true;
    }
}
