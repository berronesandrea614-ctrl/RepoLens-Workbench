package com.repolens.domain.vo;

import lombok.Data;

/**
 * K方案分支量化指标 VO。
 */
@Data
public class BranchMetricsVO {

    /** 本分支影响的文件数。 */
    private int filesChanged;

    /** 爆炸半径大小（受影响符号/引用数量）。 */
    private int blastRadiusSize;

    /** 技术债变化量（正=增债，负=还债）。 */
    private int debtDelta;

    /** 静态自评置信度 [0,1]；P1 仅静态自评，degraded=true。 */
    private Double confidence;

    /** 是否已通过验证；P1 恒 false（未落盘，无法真测）。 */
    private boolean verified;
}
