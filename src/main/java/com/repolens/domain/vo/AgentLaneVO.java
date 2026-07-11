package com.repolens.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单条 agent 泳道视图 VO（H Mission Control P1）。
 * 聚合一次 agent_run 的计划、改动、风险、偏差、债务信息，供前端泳道渲染。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentLaneVO {

    /** agent_run.id */
    private Long laneId;

    /** 引擎类型，固定 "NATIVE" */
    private String engine;

    /** agent_run.status */
    private String status;

    /** AI 是否声称完成（null = 旧行 未解析） */
    private Boolean claimedSuccess;

    /** AI 是否声称验证通过（null = 旧行 未解析） */
    private Boolean claimedVerified;

    /** 整体思路一句话（来自 agent_run_plan.approach），无计划时为 "计划未结构化" */
    private String planLine;

    /** "改动 N 文件: a.java, b.java 等 M 个" 格式摘要 */
    private String changesLine;

    /** 本次 run 改动的文件总数 */
    private int changedFileCount;

    /** 计划偏差分析（需要 requirement → reconciliation），无 requirement 时为 null */
    private DeviationVO deviation;

    /** 本次 run 改动文件命中 RED/YELLOW 理解债务的数量 */
    private int debtCount;

    /** 风险摘要（BLOCK/WARN 数量、是否有 IRREVERSIBLE BLOCK） */
    private RiskVO risk;

    /** 是否需要人工关注（hasIrreversibleBlock || blockCount>0 || trustFlag≠OK || missingCount>0） */
    private boolean needsAttention;

    /** 该泳道是否降级（组装失败时 true，仅 laneId/status/degraded 有值） */
    private boolean degraded;
}
