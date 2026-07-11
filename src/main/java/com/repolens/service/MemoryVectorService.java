package com.repolens.service;

import java.util.List;

/**
 * 长期记忆向量索引服务。
 * 职责：把 agent_memory 条目的 embedding 存入 Milvus 专用 collection，
 * 并在召回时通过余弦相似度返回候选 memoryId 列表。
 *
 * 设计决策：与 MilvusService（代码块索引）分开，原因：
 * 1. Schema 完全不同（chunk_id VarChar PK vs memory_id Int64 PK；字段集合不同）。
 * 2. 生命周期不同：代码块随仓库索引全量重建，记忆条目逐条增量 upsert/delete。
 * 3. 过滤维度不同：代码块按 repo_id 过滤，记忆按 (user_id, repo_id) 联合过滤。
 * 4. 隔离故障域：记忆向量索引失败不应影响 RAG 代码检索的可用性。
 *
 * 所有方法均为失败安全（fail-safe）：任何 Milvus 异常都被吞掉并记录 warn，
 * 绝不向调用方抛出，保证主流程不受影响。
 */
public interface MemoryVectorService {

    /**
     * 向量搜索命中结果：记忆 ID + Milvus COSINE 相似度分（来自 SearchResp hit.getScore()）。
     * score 范围 [−1, 1]；对归一化 embedding（sentence-transformers 等）通常为 [0, 1]。
     */
    record MemoryHit(long memoryId, float score) {}

    /**
     * 将单条记忆的 embedding 向量 upsert 进 Milvus。
     * Milvus 不可用或 embedding 为空时静默忽略，返回 false。
     *
     * @param memoryId  记忆 ID（Milvus PK）
     * @param userId    所属用户 ID（用于过滤）
     * @param repoId    所属仓库 ID（用于过滤）
     * @param embedding 向量数组，长度必须与 collection 维度一致
     * @return true = Milvus upsert 成功；false = 跳过（不影响主流程）
     */
    boolean upsertMemoryVector(Long memoryId, Long userId, Long repoId, float[] embedding);

    /**
     * 向量相似度召回：在 (userId, repoId) 范围内检索最相似的 topK 条记忆，
     * 返回含真实 COSINE 分的命中列表（来自 Milvus SearchResp）。
     * 返回空列表表示 Milvus 不可用或无命中，调用方应降级到关键词路径。
     *
     * @param queryVector 查询向量
     * @param userId      过滤用户
     * @param repoId      过滤仓库
     * @param topK        最多返回多少个候选
     * @return 按余弦相似度降序排列的命中列表（含真实分，可能为空）
     */
    List<MemoryHit> searchSimilar(float[] queryVector, Long userId, Long repoId, int topK);

    /**
     * 删除指定记忆对应的向量条目。
     * Milvus 不可用时静默忽略，不影响主流程。
     *
     * @param memoryId 记忆 ID
     */
    void deleteMemoryVector(Long memoryId);
}
