package com.repolens.domain.vo;

import lombok.Data;

/**
 * K方案分支图单节点 VO。
 */
@Data
public class BranchNodeVO {

    /** solution_branch.id */
    private Long id;

    /** 分支标识符，如 v0/v1/v2/v3。 */
    private String branchId;

    /** 分支内变体序号。 */
    private int variantIndex;

    /** 父分支 ID（CYOA 树；P1 恒 NULL）。 */
    private Long parentBranchId;

    /** 触发本分支的 agent_run.id；可为 NULL。 */
    private Long agentRunId;

    /** 人类可读的分支标签。 */
    private String label;

    /** 本方案的实现思路描述。 */
    private String approach;

    /** 策略提示，用于前端渲染角标。 */
    private String strategyHint;

    /**
     * 分支生命周期状态：GENERATING / READY / SELECTED / DISCARDED。
     */
    private String status;

    /** 量化指标。 */
    private BranchMetricsVO metrics;

    /** 是否降级评估；P1 恒 true（confidence 仅静态自评）。 */
    private boolean degraded;
}
