package com.repolens.service.impl.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD 单测：SensitiveFileComputer 纯逻辑——归一化/除零/BLOCK/WARN阈值/排序/topN/aiRatio clamp。
 * 无 Spring 上下文，无 Mock，纯函数。
 */
class SensitiveFileComputerTest {

    private final SensitiveFileComputer computer = new SensitiveFileComputer();

    // ── Test 1: 归一化 ──────────────────────────────────────────────────────────

    @Test
    void normalization_fanIn10vs5_nFanIn1dot0vs0dot5() {
        var a = new SensitiveFileComputer.Candidate("A.java", 10, 0, 0.0, false);
        var b = new SensitiveFileComputer.Candidate("B.java", 5,  0, 0.0, false);

        List<SensitiveFileComputer.Scored> result = computer.compute(List.of(a, b), 10);

        SensitiveFileComputer.Scored scoredA = result.stream()
                .filter(s -> s.filePath().equals("A.java")).findFirst().orElseThrow();
        SensitiveFileComputer.Scored scoredB = result.stream()
                .filter(s -> s.filePath().equals("B.java")).findFirst().orElseThrow();

        assertThat(scoredA.normalizedSignals().get("fanIn")).isEqualTo(1.0);
        assertThat(scoredB.normalizedSignals().get("fanIn")).isEqualTo(0.5);
    }

    // ── Test 2: 全零 fanIn 不除零 ──────────────────────────────────────────────

    @Test
    void allZeroFanIn_nFanIn0_noException() {
        var a = new SensitiveFileComputer.Candidate("X.java", 0, 0, 0.0, false);
        var b = new SensitiveFileComputer.Candidate("Y.java", 0, 0, 0.0, false);

        List<SensitiveFileComputer.Scored> result = computer.compute(List.of(a, b), 10);

        result.forEach(s -> assertThat(s.normalizedSignals().get("fanIn")).isEqualTo(0.0));
    }

    // ── Test 3: pathForbidden=true → severity BLOCK(不看分) ───────────────────

    @Test
    void pathForbidden_lowScore_severityBlock() {
        // fanIn=0,churn=0,aiRatio=0 → finalScore=0，但 pathForbidden=true
        var c = new SensitiveFileComputer.Candidate("secret.java", 0, 0, 0.0, true);

        List<SensitiveFileComputer.Scored> result = computer.compute(List.of(c), 10);

        assertThat(result).hasSize(1);
        SensitiveFileComputer.Scored s = result.get(0);
        assertThat(s.severity()).isEqualTo("BLOCK");
        assertThat(s.reason()).contains("禁改路径");
    }

    // ── Test 4: 高分无约束 → WARN / INFO ──────────────────────────────────────

    @Test
    void highScore_noConstraint_warnOrInfo() {
        // fanIn=10 (max), churn=10 (max), aiRatio=1.0 → finalScore = round(100*(0.3+0.2+0.2+0)) = 70 → WARN
        var warn = new SensitiveFileComputer.Candidate("Hot.java", 10, 10, 1.0, false);
        // fanIn=1 (=max here), churn=0, aiRatio=0 → score=round(100*0.3)=30 → INFO
        var info = new SensitiveFileComputer.Candidate("Cold.java", 1,  0, 0.0, false);

        // Two separate single-candidate runs so each normalizes independently
        List<SensitiveFileComputer.Scored> warnResult = computer.compute(List.of(warn), 10);
        List<SensitiveFileComputer.Scored> infoResult  = computer.compute(List.of(info), 10);

        assertThat(warnResult.get(0).severity()).isEqualTo("WARN");
        assertThat(warnResult.get(0).finalScore()).isGreaterThanOrEqualTo(60);

        assertThat(infoResult.get(0).severity()).isEqualTo("INFO");
        assertThat(infoResult.get(0).finalScore()).isLessThan(60);
    }

    // ── Test 5: BLOCK 排序优先于更高分非 BLOCK ─────────────────────────────────

    @Test
    void sortOrder_blockRanksAboveHigherScoreNonBlock() {
        // nonBlock: fanIn=10(max), churn=10(max), aiRatio=1.0, no constraint → score≈70
        var nonBlock = new SensitiveFileComputer.Candidate("NonBlock.java", 10, 10, 1.0, false);
        // block: zero signals but pathForbidden → score=0, severity=BLOCK
        var block    = new SensitiveFileComputer.Candidate("Block.java",    0,  0,  0.0, true);

        List<SensitiveFileComputer.Scored> result = computer.compute(List.of(nonBlock, block), 10);

        assertThat(result.get(0).filePath()).isEqualTo("Block.java");
        assertThat(result.get(0).severity()).isEqualTo("BLOCK");
        assertThat(result.get(1).filePath()).isEqualTo("NonBlock.java");
    }

    // ── Test 6: topN 截断 ─────────────────────────────────────────────────────

    @Test
    void topN_truncation_keepsOnlyTopN() {
        var a = new SensitiveFileComputer.Candidate("A.java", 10, 0, 0.0, false);
        var b = new SensitiveFileComputer.Candidate("B.java",  8, 0, 0.0, false);
        var c = new SensitiveFileComputer.Candidate("C.java",  5, 0, 0.0, false);
        var d = new SensitiveFileComputer.Candidate("D.java",  2, 0, 0.0, false);

        List<SensitiveFileComputer.Scored> result = computer.compute(List.of(a, b, c, d), 2);

        assertThat(result).hasSize(2);
        // A has highest fanIn ratio → top score
        assertThat(result.get(0).filePath()).isEqualTo("A.java");
    }

    // ── Test 7: aiRatio >1 clamp; reason 含 PATH_FORBIDDEN ───────────────────

    @Test
    void aiRatioClamped_and_reasonMentionsPathForbidden() {
        // aiRatio=1.5 should be clamped to 1.0
        var c = new SensitiveFileComputer.Candidate("Ai.java", 0, 0, 1.5, true);

        List<SensitiveFileComputer.Scored> result = computer.compute(List.of(c), 10);

        SensitiveFileComputer.Scored s = result.get(0);
        assertThat(s.normalizedSignals().get("aiRatio")).isEqualTo(1.0);
        // finalScore = round(100*(0+0+0.2*1.0+0.3*1.0)) = round(50) = 50
        assertThat(s.finalScore()).isEqualTo(50);
        assertThat(s.severity()).isEqualTo("BLOCK");
        assertThat(s.reason()).contains("禁改路径");
    }
}
