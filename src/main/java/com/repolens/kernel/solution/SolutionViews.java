package com.repolens.kernel.solution;

import java.util.List;

/**
 * M8 多方案对比引擎对外返回的只读视图（纯内核 record，不进 god class 的 domain.vo 命名空间）。
 *
 * <p>未来 app 侧的 controller/前端可把这些 record 映射成 HTTP VO；引擎本身只吐结构化事实，
 * 谁真实（{@code metricKind=REAL}）、谁未验证（{@code verified=null}）都如实标注，供上层诚实展示。
 */
public final class SolutionViews {

    private SolutionViews() {
    }

    /**
     * 一个方案组的完整视图：状态 + 选中/推荐 + 各分支明细。
     * 聊天卡组与可视化对比窗都读它（前端单一状态源的后端映像）。
     */
    public record SolutionSetView(
            Long setId,
            Long repoId,
            Long sessionId,
            String engine,
            String status,
            Long selectedBranchId,
            Long recommendedBranchId,
            List<SolutionBranchView> branches) {
    }

    /**
     * 单个方案分支的对比明细。指标全部来自真实 staged 改动统计。
     *
     * @param recommended 是否被引擎打分推荐（⭐）——建议非强制，最终用户选。
     */
    public record SolutionBranchView(
            Long branchId,
            String label,
            String strategyHint,
            int variantIndex,
            String metricKind,
            String status,
            int filesChanged,
            int linesAdded,
            int linesRemoved,
            long tokensSpent,
            int turns,
            Boolean verified,
            String terminationReason,
            String finalText,
            boolean recommended) {
    }
}
