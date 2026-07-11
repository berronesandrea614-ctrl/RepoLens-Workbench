package com.repolens.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Mission Control 顶层摘要 VO（H Mission Control P1）。
 * 聚合所有泳道的整体统计，供面板 header 快速展示。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SummaryVO {

    /** 泳道总数（= 查询的 agent_run 数量） */
    private int laneCount;

    /** 所有泳道中 BLOCK 级风险总计 */
    private int totalBlockRisks;

    /** 所有泳道中 WARN 级风险总计 */
    private int totalWarnRisks;

    /** 债务文件 RED 档总数（该 repo 全量） */
    private int redDebtFiles;

    /** 债务文件 YELLOW 档总数（该 repo 全量） */
    private int yellowDebtFiles;

    /** 需要人工关注的泳道数量 */
    private int needsAttentionCount;
}
