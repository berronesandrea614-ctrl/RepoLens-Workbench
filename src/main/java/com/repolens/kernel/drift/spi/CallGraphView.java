package com.repolens.kernel.drift.spi;

import java.util.List;

/**
 * 某一时刻的「当前整张调用图」只读视图（{@link CallGraphSnapshotProvider#currentGraph} 的返回）。
 *
 * <p>是隔壁窗口当前态的一次全量投影：所有符号节点 + 依赖边 + 文件级指纹。内核拿到后做稳定 key 归一、
 * 算图哈希、落 {@code rk_*} 快照，供跨时间漂移比对——本视图本身<b>不带时间维度</b>（时间由内核快照的
 * {@code captured_at}/{@code graph_hash} 承载）。
 *
 * @param repoId 仓库 id
 * @param nodes  全部符号节点
 * @param edges  全部依赖边（source 指向本视图内某节点，target 为名字）
 * @param files  全部文件级指纹（content_hash / last_commit_id）
 */
public record CallGraphView(long repoId,
                            List<SymbolNode> nodes,
                            List<DependencyEdge> edges,
                            List<FileFingerprint> files) {

    /** 归一空视图（未索引/空仓）。 */
    public static CallGraphView empty(long repoId) {
        return new CallGraphView(repoId, List.of(), List.of(), List.of());
    }
}
