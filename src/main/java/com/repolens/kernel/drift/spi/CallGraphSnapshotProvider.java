package com.repolens.kernel.drift.spi;

/**
 * M9 架构漂移 · 调用图只读边界（反腐层端口）。
 *
 * <p>这是内核消费「当前整张调用图」的<b>唯一入口</b>。内核对隔壁窗口（调用图/解析线）的
 * {@code code_symbol}/{@code code_dependency}/{@code code_file} 表与其 Graph/Symbol VO、graphApi/symbolApi
 * <b>保持零引用</b>（跨窗口契约冻结）——只依赖本端口 + 本包 DTO（{@link CallGraphView} 等，均内核自有）。
 *
 * <p>实现由调用图窗口在其 zone 提供：把现有 {@code codeSymbolMapper/codeDependencyMapper/codeFileMapper}
 * 的 {@code selectList(repoId)} 结果映射成本包 DTO，无需改任何 schema。内核侧只在需要时调 {@link #currentGraph}
 * 拿「当前态」，自己做快照捕获（{@code rk_*} 表）、稳定 key 归一、图哈希、跨快照漂移比对。
 *
 * <p>关键约定：调用图数据无时间/版本维度（每次索引全量覆盖只留当前一份），且 {@code code_symbol.id}
 * 重索引会重置——故本端口只保证「返回某 repo 当下这一份全量图」，跨时间的历史由内核侧快照负责；
 * DTO 里的 {@code symbolId} 仅供<b>同一份快照内</b>把边关联到节点用，<b>跨快照不作身份</b>（内核用语义 key）。
 */
public interface CallGraphSnapshotProvider {

    /**
     * 返回某 repo 的当前整张调用图（全部符号 + 依赖 + 文件级指纹）。
     * 无数据（未索引/空仓）应返回空图（nodes/edges/files 皆空列表），不得抛异常、不得返回 null。
     */
    CallGraphView currentGraph(long repoId);
}
