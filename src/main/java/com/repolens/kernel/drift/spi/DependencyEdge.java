package com.repolens.kernel.drift.spi;

/**
 * 调用图里的一条依赖边（对应隔壁 {@code code_dependency} 一行）。
 *
 * <p>{@code sourceSymbolId} 指向<b>同一份快照内</b>某个 {@link SymbolNode#symbolId()}；{@code targetSymbolName}
 * 是目标符号名（隔壁 {@code target_symbol_name}，未必解析到具体符号 id）。内核做跨快照边漂移比对时，
 * source 侧用其节点的 {@link SymbolNode#stableKey()}，target 侧用名字，组合成稳定的边 key。
 *
 * @param sourceSymbolId   源符号 id（同快照内关联到 {@link SymbolNode}）
 * @param targetSymbolName 目标符号名
 * @param relationType     关系类型（隔壁 {@code relation_type} 原样，如 CALL/IMPLEMENTS）
 * @param confidence       置信度
 */
public record DependencyEdge(long sourceSymbolId,
                             String targetSymbolName,
                             String relationType,
                             double confidence) {
}
