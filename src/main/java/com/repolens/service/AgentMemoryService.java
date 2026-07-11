package com.repolens.service;

import com.repolens.domain.entity.AgentMemoryEntity;
import com.repolens.domain.vo.AgentMemoryVO;

import java.util.List;

/**
 * Agent 长期记忆存储层。负责在某 (user, repo) 维度下沉淀、检索、列出与删除记忆条目。
 */
public interface AgentMemoryService {

    /**
     * 记住一条笔记（带类型与重要性）：归一化去重 + 封顶淘汰。
     * 实现类实现此 7 参数版本。
     */
    void remember(Long userId, Long repoId, String content, String keywords,
                  String memoryType, int importance, Long sessionId);

    /**
     * 向后兼容的 5 参数版本，委托给 7 参数版本（memoryType=FACT, importance=3）。
     */
    default void remember(Long userId, Long repoId, String content, String keywords, Long sessionId) {
        remember(userId, repoId, content, keywords, "FACT", 3, sessionId);
    }

    /**
     * 召回：按关键词重叠数 desc、createdAt desc 排序取 topK；无重叠时回退返回最近 K 条。
     */
    List<AgentMemoryEntity> recall(Long userId, Long repoId, String queryKeywords, int topK);

    /**
     * 列出该 (user, repo) 全部记忆，最新在前。
     */
    List<AgentMemoryVO> list(Long userId, Long repoId);

    /**
     * 删除该 (user, repo) 下指定记忆条目。
     */
    void forget(Long userId, Long repoId, Long memoryId);
}
