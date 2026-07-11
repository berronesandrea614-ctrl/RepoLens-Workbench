package com.repolens.service.support;

import com.repolens.service.impl.support.DebtScoring;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * TDD 单元测试：DebtScoring 纯函数验证。
 *
 * <p>手算验证（来自设计规格 §1 数值示例）：
 * PaymentService.java：300 行、AI 改 210 行、level1（仅点接受）、
 * 无 rationale、cognitive 22、churn 3 次（近 14d）、无测试。
 *
 * <pre>
 * S1 = min(1, 210/300)    = 0.70
 * S2 = 0.6  (level1)
 * S3 = 1.0  (无 rationale)
 * S4 = min(1, 22/25)      = 0.88
 * S5 = min(1, 3/10)       = 0.30
 * S6 = 0.5  (降级固定)
 * S7 = 1.0  (无测试)
 * base = 0.15*0.70 + 0.15*0.6 + 0.10*1.0 + 0.20*0.88 + 0.20*0.30 + 0.10*0.5 + 0.10*1.0
 *      = 0.105 + 0.090 + 0.100 + 0.176 + 0.060 + 0.050 + 0.100
 *      = 0.681
 * amp  = 1.5  (S1≥0.5 && level1∈{0,1} && S4=0.88≥0.6)
 * score= round(100 * min(1, 0.681*1.5)) = round(100 * 1.0) = 100
 * band = RED
 * </pre>
 *
 * 手算 ±2 验收范围：score ∈ [98, 100]，实际 = 100（封顶）。
 */
class DebtScoringTest {

    // ================================================================ //
    //  Signal normalization                                              //
    // ================================================================ //

    @Test
    void s1_zero_whenNoAiLines() {
        assertThat(DebtScoring.s1(0, 300)).isEqualTo(0.0);
    }

    @Test
    void s1_clampedAtOne_whenAiLinesExceedsLineCount() {
        assertThat(DebtScoring.s1(500, 300)).isEqualTo(1.0);
    }

    @Test
    void s1_partialCoverage() {
        assertThat(DebtScoring.s1(210, 300)).isCloseTo(0.70, within(0.001));
    }

    @Test
    void s1_handlesZeroLineCount() {
        // lineCount=0 → max(0,1)=1 → s1=min(1, aiLines/1)
        assertThat(DebtScoring.s1(5, 0)).isEqualTo(1.0); // clamped
    }

    @Test
    void s2_level0_isOne() {
        assertThat(DebtScoring.s2(0)).isEqualTo(1.0);
    }

    @Test
    void s2_level1_isPoint6() {
        assertThat(DebtScoring.s2(1)).isEqualTo(0.6);
    }

    @Test
    void s2_level2_isPoint2() {
        assertThat(DebtScoring.s2(2)).isEqualTo(0.2);
    }

    @Test
    void s2_level3_isZero() {
        assertThat(DebtScoring.s2(3)).isEqualTo(0.0);
    }

    @Test
    void s3_withRationale_isZero() {
        assertThat(DebtScoring.s3(true)).isEqualTo(0.0);
    }

    @Test
    void s3_withoutRationale_isOne() {
        assertThat(DebtScoring.s3(false)).isEqualTo(1.0);
    }

    @Test
    void s4_zero_whenNoCognitive() {
        assertThat(DebtScoring.s4(0)).isEqualTo(0.0);
    }

    @Test
    void s4_fullScore_atOrAbove25() {
        assertThat(DebtScoring.s4(25)).isEqualTo(1.0);
        assertThat(DebtScoring.s4(30)).isEqualTo(1.0); // clamped
    }

    @Test
    void s4_partial() {
        assertThat(DebtScoring.s4(22)).isCloseTo(22.0 / 25.0, within(0.001)); // 0.88
    }

    @Test
    void s5_zero_whenNoChurn() {
        assertThat(DebtScoring.s5(0)).isEqualTo(0.0);
    }

    @Test
    void s5_clampedAtOne() {
        assertThat(DebtScoring.s5(15)).isEqualTo(1.0);
    }

    @Test
    void s5_partial() {
        assertThat(DebtScoring.s5(3)).isCloseTo(0.30, within(0.001));
    }

    @Test
    void s7_withTestFile_isPoint3() {
        assertThat(DebtScoring.s7(true)).isEqualTo(0.3);
    }

    @Test
    void s7_withoutTestFile_isOne() {
        assertThat(DebtScoring.s7(false)).isEqualTo(1.0);
    }

    // ================================================================ //
    //  Aggregation                                                       //
    // ================================================================ //

    @Test
    void ampFactor_isOnePointFive_whenAllConditionsMet() {
        // S1≥0.5, level∈{0,1}, S4≥0.6
        assertThat(DebtScoring.ampFactor(0.70, 1, 0.88)).isEqualTo(1.5);
        assertThat(DebtScoring.ampFactor(0.50, 0, 0.60)).isEqualTo(1.5);
    }

    @Test
    void ampFactor_isOne_whenS1Low() {
        assertThat(DebtScoring.ampFactor(0.30, 0, 0.88)).isEqualTo(1.0);
    }

    @Test
    void ampFactor_isOne_whenHighReviewLevel() {
        assertThat(DebtScoring.ampFactor(0.70, 2, 0.88)).isEqualTo(1.0);
        assertThat(DebtScoring.ampFactor(0.70, 3, 0.88)).isEqualTo(1.0);
    }

    @Test
    void ampFactor_isOne_whenLowS4() {
        assertThat(DebtScoring.ampFactor(0.70, 1, 0.50)).isEqualTo(1.0);
    }

    @Test
    void band_red_forScoreAtOrAbove70() {
        assertThat(DebtScoring.band(70)).isEqualTo("RED");
        assertThat(DebtScoring.band(100)).isEqualTo("RED");
    }

    @Test
    void band_yellow_forScoreBetween40And69() {
        assertThat(DebtScoring.band(40)).isEqualTo("YELLOW");
        assertThat(DebtScoring.band(69)).isEqualTo("YELLOW");
    }

    @Test
    void band_green_forScoreBelow40() {
        assertThat(DebtScoring.band(0)).isEqualTo("GREEN");
        assertThat(DebtScoring.band(39)).isEqualTo("GREEN");
    }

    // ================================================================ //
    //  Hand-calc verification (PaymentService.java 示例)                //
    // ================================================================ //

    @Test
    void handCalc_paymentService_scoreShouldBe100() {
        double s1 = DebtScoring.s1(210, 300);     // 0.70
        double s2 = DebtScoring.s2(1);             // 0.60 (level1)
        double s3 = DebtScoring.s3(false);          // 1.0
        double s4 = DebtScoring.s4(22);             // 0.88
        double s5 = DebtScoring.s5(3);              // 0.30
        double s7 = DebtScoring.s7(false);          // 1.0

        double base = DebtScoring.base(s1, s2, s3, s4, s5, s7);
        double amp  = DebtScoring.ampFactor(s1, 1, s4);
        int score   = DebtScoring.finalScore(base, amp);

        // base ≈ 0.681, amp = 1.5, base*amp ≈ 1.022 → capped at 1.0 → score = 100
        assertThat(s1).isCloseTo(0.70, within(0.001));
        assertThat(s4).isCloseTo(0.88, within(0.001));
        assertThat(amp).isEqualTo(1.5);
        assertThat(score).isBetween(98, 100); // ±2 允许误差
        assertThat(DebtScoring.band(score)).isEqualTo("RED");
    }

    @Test
    void handCalc_humanOnly_noChange_greenScore() {
        // 纯人写文件：S1=0（准入闸门，不进榜，但万一计算也应很低）
        double s1 = DebtScoring.s1(0, 300);   // 0.0
        double s2 = DebtScoring.s2(3);         // 0.0 (passed quiz)
        double s3 = DebtScoring.s3(true);      // 0.0
        double s4 = DebtScoring.s4(0);          // 0.0
        double s5 = DebtScoring.s5(0);          // 0.0
        double s7 = DebtScoring.s7(true);       // 0.3

        double base  = DebtScoring.base(s1, s2, s3, s4, s5, s7);
        double amp   = DebtScoring.ampFactor(s1, 3, s4);
        int    score = DebtScoring.finalScore(base, amp);

        // base = 0 + 0 + 0 + 0 + 0 + 0.10*0.5 + 0.10*0.3 = 0.08 → score = 8 GREEN
        assertThat(score).isLessThan(40);
        assertThat(DebtScoring.band(score)).isEqualTo("GREEN");
    }

    @Test
    void handCalc_afterQuiz_scoreDrops() {
        // 原来 level0（从未复核），复核后 level3（过测验）
        double s1 = DebtScoring.s1(150, 300);  // 0.50
        double s4 = DebtScoring.s4(18);          // 0.72

        double s2Before = DebtScoring.s2(0);
        double s2After  = DebtScoring.s2(3);

        double baseBefore = DebtScoring.base(s1, s2Before, 1.0, s4, 0.1, 1.0);
        double baseAfter  = DebtScoring.base(s1, s2After,  1.0, s4, 0.1, 1.0);

        int scoreBefore = DebtScoring.finalScore(baseBefore, DebtScoring.ampFactor(s1, 0, s4));
        int scoreAfter  = DebtScoring.finalScore(baseAfter,  DebtScoring.ampFactor(s1, 3, s4));

        // After passing quiz: S2 drops from 1.0→0.0, amp drops 1.5→1.0 → score falls
        assertThat(scoreAfter).isLessThan(scoreBefore);
    }
}
