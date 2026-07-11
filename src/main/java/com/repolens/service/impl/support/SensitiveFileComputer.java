package com.repolens.service.impl.support;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Feature I (自动 ADR P1): 候选敏感文件加权评分、归一化、定 severity、排序取 topN。
 * 纯函数，无 DB 访问，完全可单测。
 *
 * <p>权重: fanIn=.30 / churn=.20 / aiRatio=.20 / constraintHit=.30 (合计 1.0)</p>
 */
@Component
public class SensitiveFileComputer {

    // ── 权重常量 ──────────────────────────────────────────────────────────────

    private static final double W_FAN_IN      = 0.30;
    private static final double W_CHURN       = 0.20;
    private static final double W_AI_RATIO    = 0.20;
    private static final double W_CONSTRAINT  = 0.30;

    // ── 公共记录类型 ──────────────────────────────────────────────────────────

    /**
     * 候选文件的原始信号。
     *
     * @param filePath      仓库相对路径
     * @param fanIn         被引用/导入次数
     * @param churn         最近 N 天提交行数（或次数）
     * @param aiRatio       AI 代码占比 [0,1]（调用方保证，超出范围由本类 clamp）
     * @param pathForbidden 是否命中约束规则（路径禁改）
     */
    public record Candidate(
            String filePath,
            int fanIn,
            int churn,
            double aiRatio,
            boolean pathForbidden) {}

    /**
     * 评分后的输出结果。
     *
     * @param filePath         仓库相对路径
     * @param fanIn            原始 fanIn
     * @param churn            原始 churn
     * @param aiRatio          原始 aiRatio（clamp 前）
     * @param constraintHit    是否命中约束（等同 pathForbidden）
     * @param finalScore       [0,100] 加权综合分
     * @param severity         BLOCK / WARN / INFO
     * @param reason           主导信号中文描述
     * @param normalizedSignals 各信号归一化值 [0,1]，供前端信号条使用
     */
    public record Scored(
            String filePath,
            int fanIn,
            int churn,
            double aiRatio,
            boolean constraintHit,
            int finalScore,
            String severity,
            String reason,
            Map<String, Double> normalizedSignals) {}

    // ── 核心 API ──────────────────────────────────────────────────────────────

    /**
     * 对候选文件列表评分并返回前 topN 个结果（已排序）。
     *
     * @param candidates 候选列表（可为空）
     * @param topN       最大返回数量（≤0 时返回全部）
     * @return 排序后的评分列表，长度 ≤ topN
     */
    public List<Scored> compute(List<Candidate> candidates, int topN) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        // Step 1: 计算 fanIn / churn 的最大值（用于归一化）
        int maxFanIn = candidates.stream().mapToInt(Candidate::fanIn).max().orElse(0);
        int maxChurn = candidates.stream().mapToInt(Candidate::churn).max().orElse(0);

        // Step 2: 逐一评分
        List<Scored> scored = new ArrayList<>(candidates.size());
        for (Candidate c : candidates) {
            scored.add(score(c, maxFanIn, maxChurn));
        }

        // Step 3: 排序 — BLOCK 优先 → finalScore desc → fanIn desc
        scored.sort(Comparator
                .<Scored, Integer>comparing(s -> "BLOCK".equals(s.severity()) ? 0 : 1)
                .thenComparing(Comparator.comparingInt(Scored::finalScore).reversed())
                .thenComparing(Comparator.comparingInt(Scored::fanIn).reversed()));

        // Step 4: 截断 topN
        if (topN > 0 && scored.size() > topN) {
            return scored.subList(0, topN);
        }
        return scored;
    }

    // ── 私有辅助 ──────────────────────────────────────────────────────────────

    private Scored score(Candidate c, int maxFanIn, int maxChurn) {
        // 归一化
        double nFanIn      = maxFanIn == 0 ? 0.0 : (double) c.fanIn() / maxFanIn;
        double nChurn      = maxChurn == 0 ? 0.0 : (double) c.churn() / maxChurn;
        double nAiRatio    = clamp(c.aiRatio(), 0.0, 1.0);
        double nConstraint = c.pathForbidden() ? 1.0 : 0.0;

        // finalScore
        int finalScore = (int) Math.round(
                100.0 * (W_FAN_IN * nFanIn
                       + W_CHURN  * nChurn
                       + W_AI_RATIO * nAiRatio
                       + W_CONSTRAINT * nConstraint));

        // severity
        String severity;
        if (c.pathForbidden()) {
            severity = "BLOCK";
        } else if (finalScore >= 60) {
            severity = "WARN";
        } else {
            severity = "INFO";
        }

        // reason
        String reason = buildReason(c, nFanIn, nChurn, nAiRatio);

        // normalizedSignals（前端信号条）
        Map<String, Double> normalizedSignals = new LinkedHashMap<>();
        normalizedSignals.put("fanIn",        nFanIn);
        normalizedSignals.put("churn",        nChurn);
        normalizedSignals.put("aiRatio",      nAiRatio);
        normalizedSignals.put("constraintHit", nConstraint);

        return new Scored(
                c.filePath(),
                c.fanIn(),
                c.churn(),
                c.aiRatio(),
                c.pathForbidden(),
                finalScore,
                severity,
                reason,
                normalizedSignals);
    }

    /**
     * 组合主导信号描述（只提 non-trivial 的信号；pathForbidden 必提）。
     */
    private String buildReason(Candidate c, double nFanIn, double nChurn, double nAiRatio) {
        List<String> parts = new ArrayList<>();

        if (nFanIn > 0) {
            parts.add("高被依赖(fanIn=" + c.fanIn() + ")");
        }
        if (nChurn > 0) {
            parts.add("高频改动(churn=" + c.churn() + ")");
        }
        if (nAiRatio > 0) {
            int pct = (int) Math.round(nAiRatio * 100);
            parts.add("AI主导(" + pct + "%)");
        }
        if (c.pathForbidden()) {
            parts.add("命中禁改路径规则");
        }

        if (parts.isEmpty()) {
            return "无显著风险信号";
        }
        return String.join(" · ", parts);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
