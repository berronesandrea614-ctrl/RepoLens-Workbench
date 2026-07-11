package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 单个文件的理解债务单元（债务 Top 列表中的一项）。
 */
@Data
@Builder
public class DebtUnitVO {

    private Long   fileId;
    private String filePath;

    /** 综合理解债务分 0–100。 */
    private int    score;

    /** 分档：RED / YELLOW / GREEN。 */
    private String band;

    /** 文件总行数。 */
    private int    lineCount;

    /** 七信号明细（含 amp 因子）。 */
    private SignalBreakdownVO signals;

    /** true = 至少有一个信号处于降级（无数据）状态。 */
    private boolean degraded;
}
