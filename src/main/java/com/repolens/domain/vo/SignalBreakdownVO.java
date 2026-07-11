package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 七信号明细快照（用于雷达图 + 解释文本）。
 */
@Data
@Builder
public class SignalBreakdownVO {

    // --- S1 ---
    private long   s1AiChangedLines;
    private int    s1LineCount;
    private double s1Norm;        // 归一化后 [0,1]

    // --- S2 ---
    private int    s2ReviewLevel; // 0/1/2/3
    private double s2Norm;

    // --- S3 ---
    private boolean s3HasRationale;
    private double  s3Norm;

    // --- S4 ---
    private int    s4MaxCognitive;
    private int    s4MaxCyclomatic;
    private double s4Norm;

    // --- S5 ---
    private int    s5Churn14dCount;
    private double s5Norm;

    // --- S6 (降级) ---
    private double s6Norm;     // MVP 固定 0.5
    private boolean s6Degraded;

    // --- S7 ---
    private boolean s7HasTestFile;
    private double  s7Norm;

    // 放大因子
    private double ampFactor;

    // 综合
    private double base;
    private int    score;
    private String band;
}
