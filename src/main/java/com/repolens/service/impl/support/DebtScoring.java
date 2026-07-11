package com.repolens.service.impl.support;

/**
 * 理解债务七信号评分纯函数工具类。
 *
 * <p>所有方法均为静态纯函数（无副作用、无 I/O），便于 TDD 单元测试。
 * 业务层 ComprehensionDebtServiceImpl 调用这里的方法完成计算。
 *
 * <h2>评分公式</h2>
 * <pre>
 * base  = Σ(w_i · S_i)
 *          w1=0.15  S1=min(1, aiLines/max(lineCount,1))
 *          w2=0.15  S2=reviewLevel(0→1.0 / 1→0.6 / 2→0.2 / 3→0.0)
 *          w3=0.10  S3=hasRationale ? 0.0 : 1.0
 *          w4=0.20  S4=min(1, maxCognitive/25.0)
 *          w5=0.20  S5=min(1, churn14dCount/10.0)       [MVP：以改动次数估算]
 *          w6=0.10  S6=0.5                               [MVP 固定降级中性值]
 *          w7=0.10  S7=hasTestFile ? 0.3 : 1.0
 * amp   = 1.5 if S1≥0.5 && reviewLevel∈{0,1} && S4≥0.6 else 1.0
 * score = round(100 · min(1, base · amp))
 * 分档  = score≥70 → RED / 40–69 → YELLOW / <40 → GREEN
 * </pre>
 *
 * <h2>S6 降级说明</h2>
 * MVP 阶段无 JGit git-log，S6 固定为中性值 0.5（贡献 0.10×0.5=0.05）。
 * 选此方案而非权重重分配，是为了保持总权重 1.0、公式一致，且 0.5 不偏袒任何一方。
 * VO 中标记 degraded=true，雷达图 S6 轴显示"数据不足"。
 * P1 阶段引入 JGit LogCommand 后补全。
 */
public final class DebtScoring {

    // 权重
    public static final double W1 = 0.15;
    public static final double W2 = 0.15;
    public static final double W3 = 0.10;
    public static final double W4 = 0.20;
    public static final double W5 = 0.20;
    public static final double W6 = 0.10;
    public static final double W7 = 0.10;

    /** S6 MVP 固定降级值（中性 0.5）。 */
    public static final double S6_DEGRADED = 0.5;

    /** S4 归一化分母（cognitive ≥ 25 → 满分 1.0）。 */
    static final double S4_NORM_MAX = 25.0;

    /** S5 churn 归一化分母（14 天内 10+ 次改动 → 满分 1.0）。 */
    static final double S5_NORM_MAX = 10.0;

    private DebtScoring() {}

    // ------------------------------------------------------------------ //
    //  Signal normalization                                                //
    // ------------------------------------------------------------------ //

    /**
     * S1：AI 改动占比归一化。
     *
     * @param aiChangedLines AI 估算改动行数（跨所有 file_change_log 条目）
     * @param lineCount      文件当前总行数
     */
    public static double s1(long aiChangedLines, int lineCount) {
        return Math.min(1.0, aiChangedLines / (double) Math.max(lineCount, 1));
    }

    /**
     * S2：未复核程度。reviewLevel 取最高级别（0=从未/1=仅Accept/2=看Diff/3=过测验）。
     *
     * @param reviewLevel 0,1,2,3
     */
    public static double s2(int reviewLevel) {
        return switch (reviewLevel) {
            case 3 -> 0.0;
            case 2 -> 0.2;
            case 1 -> 0.6;
            default -> 1.0;
        };
    }

    /**
     * S3：无理由记录。
     *
     * @param hasRationale 是否有 requirement_symbol 或 agent_run_plan 关联
     */
    public static double s3(boolean hasRationale) {
        return hasRationale ? 0.0 : 1.0;
    }

    /**
     * S4：认知复杂度归一化。
     *
     * @param maxCognitive 该文件所有方法 cognitive 的最大值
     */
    public static double s4(int maxCognitive) {
        return Math.min(1.0, maxCognitive / S4_NORM_MAX);
    }

    /**
     * S5：14 天内反复重写（内部 churn）。
     *
     * @param churn14dCount 近 14 天内对该文件的 APPLIED/REVERTED 变更次数
     */
    public static double s5(int churn14dCount) {
        return Math.min(1.0, churn14dCount / S5_NORM_MAX);
    }

    /**
     * S7：测试覆盖缺口（启发式：有无对应测试文件）。
     *
     * @param hasTestFile 是否存在同名 *Test 或 test_ 文件
     */
    public static double s7(boolean hasTestFile) {
        return hasTestFile ? 0.3 : 1.0;
    }

    // ------------------------------------------------------------------ //
    //  Aggregation                                                         //
    // ------------------------------------------------------------------ //

    /**
     * 计算加权 base 分（S6 固定 0.5 降级）。
     */
    public static double base(double s1, double s2, double s3, double s4, double s5, double s7) {
        return W1 * s1 + W2 * s2 + W3 * s3 + W4 * s4 + W5 * s5 + W6 * S6_DEGRADED + W7 * s7;
    }

    /**
     * 乘性放大因子：AI 写 + 没读懂 + 复杂 = 放大 1.5x。
     *
     * @param s1          S1 归一化值
     * @param reviewLevel 复核等级（0/1=差/2/3=好）
     * @param s4          S4 归一化值
     */
    public static double ampFactor(double s1, int reviewLevel, double s4) {
        return (s1 >= 0.5 && reviewLevel <= 1 && s4 >= 0.6) ? 1.5 : 1.0;
    }

    /**
     * 最终分 0–100（整数）。
     */
    public static int finalScore(double base, double amp) {
        return (int) Math.round(100.0 * Math.min(1.0, base * amp));
    }

    /**
     * 分档：RED ≥ 70 / YELLOW 40–69 / GREEN <40。
     */
    public static String band(int score) {
        if (score >= 70) return "RED";
        if (score >= 40) return "YELLOW";
        return "GREEN";
    }
}
