package com.repolens.kernel.drift;

import java.util.List;

/**
 * M9 架构漂移引擎对外返回的只读视图（纯内核 record，不进 god class 的 domain.vo）。
 * 前端时间轴/漂移雷达消费这些结构；这里只吐结构化事实。
 */
public final class DriftViews {

    private DriftViews() {
    }

    /** 一次图快照的摘要（不含全量节点/边明细，明细在 rk_ 表里按需查）。 */
    public record SnapshotView(Long snapshotId, long repoId, Long sessionId, int seq, String label,
                               String commitRef, int nodeCount, int edgeCount, int fileCount,
                               String graphHash, String prevHash) {
    }

    /** 一处漂移。 */
    public record DriftItem(String driftType, String entityKeyHash, String entityDesc,
                            String filePath, String language,
                            Long attributedSessionId, String attributedCommit) {
    }

    /**
     * 两快照间的漂移报告。{@code changed=false} 表示两快照图哈希相同（零漂移）——
     * 这本身是个有用信号（架构在这段时间稳定）。
     */
    public record DriftReport(Long fromSnapshotId, Long toSnapshotId, String fromHash, String toHash,
                              boolean changed, List<DriftItem> drifts) {
    }

    /**
     * 沿时间的架构演化：按序快照 + 相邻快照两两之间的漂移。这是「沿会话/commit 回放调用图演化」的产物。
     */
    public record EvolutionTimeline(long repoId, List<SnapshotView> snapshots, List<DriftReport> transitions) {
    }
}
