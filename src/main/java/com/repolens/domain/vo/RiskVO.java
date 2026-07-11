package com.repolens.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 风险摘要 VO（H Mission Control P1）。
 * 聚合单次 agent_run 关联的 change_risk_flag 统计。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskVO {

    /** BLOCK 级风险数量 */
    private int blockCount;

    /** WARN 级风险数量 */
    private int warnCount;

    /** 是否存在 severity=BLOCK 且 reversibility=IRREVERSIBLE 的高危风险 */
    private boolean hasIrreversibleBlock;
}
