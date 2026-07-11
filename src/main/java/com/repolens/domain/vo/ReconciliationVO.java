package com.repolens.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 计划 vs 实际对账结果 VO（Feature B P1）。
 * 前端直接消费，同时作为 requirement_reconciliation.ledger_json 的快照格式。
 *
 * <p>降级规则：
 * <ul>
 *   <li>planned=false → degrade=true，仅做改动分类+自报核对（items 为空）。</li>
 *   <li>无 file_change_log → offPlan/items 为空。</li>
 *   <li>对账计算失败 → 不抛异常，返回 degrade=true + errorNote。</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReconciliationVO {

    /** 是否有结构化计划（false = 老会话降级）。 */
    private boolean planned;

    /** 是否降级（无计划或计算失败时 true）。 */
    private boolean degrade;

    /** 概览摘要。 */
    private Summary summary;

    /** 逐计划步对账结果（planned=false 时为空）。 */
    private List<PlanItemRecon> items;

    /** 计划外改动列表（OVER_SCOPE / SILENT_ADD）。 */
    private List<OffPlanChange> offPlan;

    /** 自报核对结果。 */
    private SelfReport selfReport;

    /** P2=约束违规列表（无约束文件时为空列表）。Only BLOCK-severity items show red. */
    private List<ConstraintViolation> violations;

    // ── 嵌套 VO ───────────────────────────────────────────────────────────────

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Summary {
        /** 计划落实率（landedFiles/declaredFiles），0.0~1.0。 */
        private double coverage;
        /** 改动契合度（IN_PLAN 改动/总改动），0.0~1.0。 */
        private double fidelity;
        /** 计划外改动数（OVER_SCOPE + SILENT_ADD）。 */
        private int offPlanCount;
        /** P2=0（约束违规数，P1 始终为 0）。 */
        private int violationCount;
        /** 综合信任标志：OK / SUSPECT / FABRICATED。 */
        private String trustFlag;
        /** 人话摘要，如「计划5文件落实4(80%)｜实际6改动有2处计划外」。 */
        private String humanLine;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlanItemRecon {
        /** 步骤 ID（来自 PlanStepJson.stepId，旧数据用 step-N 兜底）。 */
        private String stepId;
        /** 步骤标题。 */
        private String title;
        /**
         * 四态状态：
         * LANDED（已落实）/ PARTIAL（部分落实）/
         * MISSING_ATTEMPTED（未落实·读过）/ MISSING_SILENT（未落实·完全没碰）。
         */
        private String status;
        /** 声明的文件列表。 */
        private List<String> declaredFiles;
        /** 已落地的文件列表。 */
        private List<String> landedFiles;
        /** 未落地的文件列表（PARTIAL/MISSING* 时非空）。 */
        private List<String> missingFiles;
        /** 声明操作类型（CREATE/MODIFY/DELETE/null=未知）。 */
        private String declaredOp;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OffPlanChange {
        /** 文件路径。 */
        private String filePath;
        /** 分类：OVER_SCOPE（超范围）/ SILENT_ADD（静默新增·最高危）。 */
        private String classification;
        /** 关联 file_change_log.id（用于前端跳 diff）。 */
        private Long changeId;
        /** 操作类型（WRITE/CREATE/DELETE）。 */
        private String opType;
        /** 文件业务内容摘要（从改动内容解析，如「个人博客文章实体类，含 title/content/author 字段」）。 */
        private String summary;
        /** 一句话签名，如 "class PersonalBlog { id, title, content... }"。 */
        private String sig;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SelfReport {
        /** AI 是否声称完成。 */
        private boolean claimedSuccess;
        /** AI 是否声称验证/测试通过。 */
        private boolean claimedVerified;
        /** AI 声称的原话截段。 */
        private String claimEvidence;
        /** 综合信任标志（与 Summary.trustFlag 相同但单独给自报区展示）。 */
        private String trustFlag;
        /** 是否检测到「在改动落盘前跑测试」的过期验证。 */
        private boolean staleVerification;
        /** 逐项核对结果。 */
        private List<SelfReportCheck> checks;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SelfReportCheck {
        /**
         * 检查类型：
         * FABRICATED_VERIFICATION🔴 / CLAIM_CONTRADICTS_RESULT🔴 /
         * TEST_WEAKENED🔴 / NO_OP_SUCCESS🟠。
         */
        private String type;
        /** 严重程度：RED（🔴）/ ORANGE（🟠）。 */
        private String severity;
        /** 人话说明（前端展示用）。 */
        private String detail;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConstraintViolation {
        /**
         * Rule type: PATH_FORBIDDEN / FILETYPE_FORBIDDEN / NO_NEW_DEP / MUST_VERIFY /
         * KEEP_SCOPE / SEMANTIC.
         */
        private String ruleType;
        /** Original rule sentence from AGENTS.md. */
        private String rawText;
        /** Files that triggered this violation (empty list for MUST_VERIFY). */
        private List<String> matchedFiles;
        /** BLOCK (shown red) or WARN (advisory). */
        private String severity;
    }
}
