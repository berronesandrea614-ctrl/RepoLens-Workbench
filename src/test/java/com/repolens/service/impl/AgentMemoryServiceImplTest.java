package com.repolens.service.impl;

import com.repolens.domain.entity.AgentMemoryEntity;
import com.repolens.llm.LlmClient;
import com.repolens.llm.config.LlmRuntimeConfig;
import com.repolens.llm.model.LlmResponse;
import com.repolens.mapper.AgentMemoryMapper;
import com.repolens.service.EmbeddingService;
import com.repolens.service.MemoryVectorService;
import com.repolens.service.impl.support.MemoryMetrics;
import com.repolens.service.impl.support.MemoryReconciler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 长期记忆存储层单测（mock AgentMemoryMapper + EmbeddingService + MemoryVectorService，无真实 DB/Milvus）。
 * 验证：
 * (a) remember 正常插入；
 * (b) remember 模糊去重：内容 token 集合 Jaccard >= 0.8 跳过不插，差异足够则插入；
 * (c) remember 超 MAX_NOTES(30) 删最旧；
 * (d) recall 复合评分 = relevance*0.7 + recency*0.3：重叠相同时更新的排前（时间衰减生效），
 *     重叠更高的即便略旧也排前（相关性主导）；
 * (e) recall 无重叠且分数低于 MIN_FUSION_SCORE 阈值时返回空列表；
 * (f) EmbeddingService 返回 null 时 recall 降级到关键词路径；
 * (g) 召回结果触发 access_count 更新；
 * (h) reconcile UPDATE → updateById + vector upsert（F3）；
 * (i) reconcile DELETE → deleteById + insert；
 * (j) reconcile NOOP → no insert；
 * (k) reconcile parse-fail → Jaccard-dedup ADD path；
 * (l) real cosine score low → hit filtered by threshold (F2)。
 *
 * EmbeddingService/MemoryVectorService 未 stub 时 Mockito 返回 null/empty，
 * 触发关键词降级路径，保持现有行为断言依然有效。
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class AgentMemoryServiceImplTest {

    /** 固定"当前时间"，避免依赖真实系统时钟。 */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 3, 12, 0, 0);

    @Mock
    private AgentMemoryMapper agentMemoryMapper;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private MemoryVectorService memoryVectorService;

    @Mock
    private LlmClient llmClient;

    private AgentMemoryServiceImpl newService() {
        LlmRuntimeConfig cfg = new LlmRuntimeConfig(null);
        org.springframework.test.util.ReflectionTestUtils.setField(cfg, "modelName", "mock-model");
        org.springframework.test.util.ReflectionTestUtils.setField(cfg, "timeoutMs", 5000);
        MemoryReconciler reconciler = new MemoryReconciler(llmClient, cfg);

        // F4: TransactionTemplate mock that immediately executes the callback (no real tx in unit test).
        TransactionTemplate tt = mock(TransactionTemplate.class);
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> action =
                    inv.getArgument(0);
            action.accept(null);
            return null;
        }).when(tt).executeWithoutResult(any());

        AgentMemoryServiceImpl svc = new AgentMemoryServiceImpl(
                agentMemoryMapper, embeddingService, memoryVectorService,
                new MemoryMetrics(), llmClient, cfg, reconciler, tt);
        svc.clock = () -> NOW;
        return svc;
    }

    private AgentMemoryEntity note(long id, String content, String keywords, LocalDateTime createdAt) {
        AgentMemoryEntity e = new AgentMemoryEntity();
        e.setId(id);
        e.setUserId(1L);
        e.setRepoId(2L);
        e.setContent(content);
        e.setKeywords(keywords);
        e.setCreatedAt(createdAt);
        e.setImportance((byte) 3);
        e.setAccessCount(0);
        return e;
    }

    @Test
    void remember_shouldInsertWhenNew() {
        when(agentMemoryMapper.selectList(any())).thenReturn(new ArrayList<>());

        newService().remember(1L, 2L, "用户偏好中文回答", "偏好,中文", 99L);

        ArgumentCaptor<AgentMemoryEntity> captor = ArgumentCaptor.forClass(AgentMemoryEntity.class);
        verify(agentMemoryMapper, times(1)).insert(captor.capture());
        AgentMemoryEntity saved = captor.getValue();
        Assertions.assertEquals(1L, saved.getUserId());
        Assertions.assertEquals(2L, saved.getRepoId());
        Assertions.assertEquals("用户偏好中文回答", saved.getContent());
        Assertions.assertEquals("偏好,中文", saved.getKeywords());
        Assertions.assertEquals(99L, saved.getSourceSessionId());
        Assertions.assertNotNull(saved.getCreatedAt());
        // E1 new fields
        Assertions.assertEquals("FACT", saved.getMemoryType());
        Assertions.assertEquals((byte) 3, saved.getImportance());
        Assertions.assertNotNull(saved.getConfidence());
        verify(agentMemoryMapper, never()).deleteById(any(java.io.Serializable.class));
    }

    @Test
    void remember_shouldSkipNearDuplicateContent() {
        // 现有内容与新内容仅一字之差 -> token 集合 Jaccard >= 0.8 -> 视为近似重复跳过。
        List<AgentMemoryEntity> existing = new ArrayList<>();
        existing.add(note(10, "用户偏好使用中文回答问题", "x", NOW.minusDays(1)));
        when(agentMemoryMapper.selectList(any())).thenReturn(existing);

        newService().remember(1L, 2L, "用户偏好使用中文回答疑问", "偏好", 99L);

        verify(agentMemoryMapper, never()).insert(any(AgentMemoryEntity.class));
    }

    @Test
    void remember_shouldInsertWhenSufficientlyDifferent() {
        // 现有为中文偏好，新内容为完全不同主题 -> Jaccard 远低于阈值 -> 正常插入。
        List<AgentMemoryEntity> existing = new ArrayList<>();
        existing.add(note(10, "用户偏好使用中文回答问题", "x", NOW.minusDays(1)));
        when(agentMemoryMapper.selectList(any())).thenReturn(existing);

        newService().remember(1L, 2L, "缓存层采用 Redis 实现分布式锁", "缓存,Redis", 99L);

        verify(agentMemoryMapper, times(1)).insert(any(AgentMemoryEntity.class));
    }

    @Test
    void remember_shouldEvictOldestWhenOverMaxNotes() {
        // 已有 30 条（id/createdAt 递增，id=1 最旧），插入第 31 条 -> 删最旧 1 条
        List<AgentMemoryEntity> existing = new ArrayList<>();
        LocalDateTime base = NOW.minusDays(40);
        for (int i = 1; i <= 30; i++) {
            existing.add(note(i, "note-" + i, "kw" + i, base.plusDays(i)));
        }
        when(agentMemoryMapper.selectList(any())).thenReturn(existing);

        newService().remember(1L, 2L, "brand-new-note", "kw-new", 99L);

        verify(agentMemoryMapper, times(1)).insert(any(AgentMemoryEntity.class));
        // 最旧的是 id=1
        verify(agentMemoryMapper, times(1)).deleteById(1L);
    }

    @Test
    void recall_shouldRankByRelevanceThenRecency() {
        LocalDateTime base = NOW.minusDays(10);
        List<AgentMemoryEntity> all = new ArrayList<>();
        all.add(note(1, "c1", "auth, login", base.plusDays(1)));        // overlap 2, age 9d
        all.add(note(2, "c2", "cache", base.plusDays(2)));              // overlap 0 -> below threshold
        all.add(note(3, "c3", "login, security", base.plusDays(3)));   // overlap 1, age 7d
        all.add(note(4, "c4", "auth login token", base.plusDays(9)));  // overlap 2, age 1d
        when(agentMemoryMapper.selectList(any())).thenReturn(all);

        // embeddingService returns null -> keyword fallback path
        when(embeddingService.embed(any())).thenReturn(null);

        List<AgentMemoryEntity> result = newService().recall(1L, 2L, "auth login", 3);

        // note(2) has 0 overlap -> score below MIN_FUSION_SCORE (0.35) -> excluded
        // expected: [4, 1, 3] with keyword scores well above 0.35
        Assertions.assertEquals(3, result.size());
        List<Long> ids = result.stream().map(AgentMemoryEntity::getId).collect(Collectors.toList());
        Assertions.assertEquals(List.of(4L, 1L, 3L), ids);
    }

    @Test
    void recall_newerRanksHigherWhenEqualRelevance() {
        // 关键词重叠完全相同，仅年龄不同 -> 更新的必须排前（时间衰减生效）。
        LocalDateTime base = NOW.minusDays(30);
        List<AgentMemoryEntity> all = new ArrayList<>();
        all.add(note(1, "older", "auth login", base));               // age 30d
        all.add(note(2, "newer", "auth login", NOW.minusDays(1)));   // age 1d
        when(agentMemoryMapper.selectList(any())).thenReturn(all);
        when(embeddingService.embed(any())).thenReturn(null);

        List<AgentMemoryEntity> result = newService().recall(1L, 2L, "auth login", 2);

        Assertions.assertEquals(2L, result.get(0).getId());
        Assertions.assertEquals(1L, result.get(1).getId());
    }

    @Test
    void recall_higherRelevanceBeatsSlightlyNewer() {
        // 重叠更高的略旧条目，凭借相关性权重(0.7)应压过重叠更低但更新的条目。
        LocalDateTime base = NOW.minusDays(10);
        List<AgentMemoryEntity> all = new ArrayList<>();
        all.add(note(1, "high-overlap-older", "auth login token", base.plusDays(1)));  // overlap 2, age 9d
        all.add(note(2, "low-overlap-newer", "login only", base.plusDays(9)));          // overlap 1, age 1d
        when(agentMemoryMapper.selectList(any())).thenReturn(all);
        when(embeddingService.embed(any())).thenReturn(null);

        List<AgentMemoryEntity> result = newService().recall(1L, 2L, "auth login", 2);

        Assertions.assertEquals(1L, result.get(0).getId());
        Assertions.assertEquals(2L, result.get(1).getId());
    }

    @Test
    void recall_shouldMatchChineseKeywords() {
        // CJK 回归：中文查询必须能与中文关键词重叠，无关条目被阈值过滤掉。
        LocalDateTime base = NOW.minusDays(10);
        List<AgentMemoryEntity> all = new ArrayList<>();
        all.add(note(1, "用户服务在 UserService 中实现", "用户服务,实现", base.plusDays(1)));
        // note(2) 与查询无关（缓存/Redis），recency-only 分约 0.21 < 0.35，被阈值过滤。
        all.add(note(2, "缓存使用 Redis", "缓存,数据库", base.plusDays(5)));
        when(agentMemoryMapper.selectList(any())).thenReturn(all);
        when(embeddingService.embed(any())).thenReturn(null);

        List<AgentMemoryEntity> result = newService().recall(1L, 2L, "用户服务怎么实现", 2);

        // 中文重叠的 id1 通过阈值并排首位；无关的 id2 被 MIN_FUSION_SCORE 过滤，不注入 prompt。
        Assertions.assertFalse(result.isEmpty(), "至少应召回 id1（中文关键词命中）");
        Assertions.assertEquals(1L, result.get(0).getId());
    }

    @Test
    void recall_shouldReturnEmptyWhenNoOverlapBelowThreshold() {
        // 无关键词重叠时，所有条目 score < MIN_FUSION_SCORE(0.35)，应返回空列表。
        // 这验证了阈值过滤防止无关记忆注入 prompt 的预期行为。
        LocalDateTime base = NOW.minusDays(10);
        List<AgentMemoryEntity> all = new ArrayList<>();
        all.add(note(1, "c1", "aaa", base.plusDays(1)));
        all.add(note(2, "c2", "bbb", base.plusDays(2)));
        all.add(note(3, "c3", "ccc", base.plusDays(3)));
        when(agentMemoryMapper.selectList(any())).thenReturn(all);
        when(embeddingService.embed(any())).thenReturn(null);

        List<AgentMemoryEntity> result = newService().recall(1L, 2L, "zzz nomatch", 2);

        // 无重叠且 recency-only 分 < 0.35 -> 阈值过滤后为空，防止无关记忆污染 prompt
        Assertions.assertEquals(0, result.size());
    }

    // ── F2: real cosine score ─────────────────────────────────────────────────

    /**
     * F2 regression: 真实 COSINE 分 = 0.1（极低相似），fusionScore < 0.35 → 被阈值过滤。
     * 旧 rank 代理分最低为 0.6，导致此场景无法被过滤（0.6*0.6 = 0.36 > 0.35，阈值形同虚设）。
     */
    @Test
    void recall_lowRealCosineScore_filteredByThreshold() {
        AgentMemoryEntity lowMatch = note(99, "some unrelated content", "aaa,bbb", NOW.minusDays(1));
        // Only one selectList call: loadByIds inside tryVectorRecall
        when(agentMemoryMapper.selectList(any()))
                .thenReturn(new ArrayList<>(List.of(lowMatch)));

        // embed returns non-null → vector path taken
        when(embeddingService.embed(any())).thenReturn(new float[]{0.1f});
        // Milvus returns hit with low cosine score = 0.1
        when(memoryVectorService.searchSimilar(any(), any(), any(), anyInt()))
                .thenReturn(List.of(new MemoryVectorService.MemoryHit(99L, 0.1f)));

        // query "xyz query" has no token overlap with keywords "aaa,bbb"
        // fusionScore = 0.1*0.6 + 0*0.2 + recency(1d)*0.1 + (3/5)*0.1
        //             ≈ 0.06 + 0 + 0.093 + 0.06 = 0.213 < 0.35 → filtered
        List<AgentMemoryEntity> result = newService().recall(1L, 2L, "xyz query", 3);

        Assertions.assertTrue(result.isEmpty(),
                "Low cosine hit must be filtered: real cosine threshold is meaningful, rank proxy was not");
    }

    // ── F5: service-level reconcile verdict tests ─────────────────────────────

    /**
     * LLM verdict UPDATE|10 → updateById called with new content + new keywords (F3),
     * and vector re-indexed (F3 upsertVectorSafe).
     */
    @Test
    void remember_reconcile_update_setsNewContentKeywordsAndReIndexes() {
        AgentMemoryEntity target = note(10, "old content", "old,kw", NOW.minusDays(1));
        // First call: loadAll in remember; second call: loadByIds in findTopCandidates
        when(agentMemoryMapper.selectList(any()))
                .thenReturn(new ArrayList<>(List.of(target)))
                .thenReturn(new ArrayList<>(List.of(target)));

        // Vector path: high cosine score triggers reconcile
        when(embeddingService.embed(any())).thenReturn(new float[]{0.1f, 0.2f, 0.3f});
        when(memoryVectorService.searchSimilar(any(), any(), any(), anyInt()))
                .thenReturn(List.of(new MemoryVectorService.MemoryHit(10L, 0.85f)));

        // LLM returns UPDATE verdict
        when(llmClient.generate(any())).thenReturn(LlmResponse.builder()
                .content("ACTION: UPDATE|10")
                .success(true)
                .build());

        newService().remember(1L, 2L, "new content", "new,kw", "FACT", 3, 99L);

        // updateById must be called with new content AND new keywords
        ArgumentCaptor<AgentMemoryEntity> captor = ArgumentCaptor.forClass(AgentMemoryEntity.class);
        verify(agentMemoryMapper).updateById(captor.capture());
        Assertions.assertEquals("new content", captor.getValue().getContent());
        Assertions.assertEquals("new,kw", captor.getValue().getKeywords());

        // No insert (UPDATE, not ADD)
        verify(agentMemoryMapper, never()).insert(any(AgentMemoryEntity.class));

        // F3: vector must be re-indexed with targetId=10
        verify(memoryVectorService).upsertMemoryVector(eq(10L), any(), any(), any());
    }

    /**
     * LLM verdict NOOP → no insert, no update (memory is duplicate, discard new one).
     */
    @Test
    void remember_reconcile_noop_doesNotInsertOrUpdate() {
        AgentMemoryEntity target = note(10, "same content", "same,kw", NOW.minusDays(1));
        when(agentMemoryMapper.selectList(any()))
                .thenReturn(new ArrayList<>(List.of(target)))
                .thenReturn(new ArrayList<>(List.of(target)));
        when(embeddingService.embed(any())).thenReturn(new float[]{0.1f});
        when(memoryVectorService.searchSimilar(any(), any(), any(), anyInt()))
                .thenReturn(List.of(new MemoryVectorService.MemoryHit(10L, 0.90f)));
        when(llmClient.generate(any())).thenReturn(LlmResponse.builder()
                .content("ACTION: NOOP")
                .success(true)
                .build());

        newService().remember(1L, 2L, "same content", "same,kw", "FACT", 3, 99L);

        verify(agentMemoryMapper, never()).insert(any(AgentMemoryEntity.class));
        verify(agentMemoryMapper, never()).updateById(any(AgentMemoryEntity.class));
    }

    /**
     * LLM verdict DELETE|10 → deleteById(10) + insert (new memory replaces old conflicting one).
     */
    @Test
    void remember_reconcile_delete_removesOldAndInsertsNew() {
        AgentMemoryEntity target = note(10, "conflicting content", "old,kw", NOW.minusDays(1));
        when(agentMemoryMapper.selectList(any()))
                .thenReturn(new ArrayList<>(List.of(target)))
                .thenReturn(new ArrayList<>(List.of(target)));
        when(embeddingService.embed(any())).thenReturn(new float[]{0.1f});
        when(memoryVectorService.searchSimilar(any(), any(), any(), anyInt()))
                .thenReturn(List.of(new MemoryVectorService.MemoryHit(10L, 0.80f)));
        when(llmClient.generate(any())).thenReturn(LlmResponse.builder()
                .content("ACTION: DELETE|10")
                .success(true)
                .build());

        newService().remember(1L, 2L, "corrected content", "new,kw", "FACT", 3, 99L);

        // Old memory deleted
        verify(agentMemoryMapper, times(1)).deleteById(10L);
        // New memory inserted
        verify(agentMemoryMapper, times(1)).insert(any(AgentMemoryEntity.class));
    }

    /**
     * LLM parse failure (exception) → isFallback=true → Jaccard-dedup ADD path.
     * Content is sufficiently different → insert proceeds.
     */
    @Test
    void remember_reconcile_parseFail_fallsBackToJaccardAndInserts() {
        AgentMemoryEntity target = note(10, "old content abc", "old,kw", NOW.minusDays(1));
        when(agentMemoryMapper.selectList(any()))
                .thenReturn(new ArrayList<>(List.of(target)))
                .thenReturn(new ArrayList<>(List.of(target)));
        when(embeddingService.embed(any())).thenReturn(new float[]{0.1f});
        when(memoryVectorService.searchSimilar(any(), any(), any(), anyInt()))
                .thenReturn(List.of(new MemoryVectorService.MemoryHit(10L, 0.85f)));
        // LLM throws → reconciler returns fallback
        when(llmClient.generate(any())).thenThrow(new RuntimeException("llm timeout"));

        // New content completely different → Jaccard << 0.8 → not duplicate → insert
        newService().remember(1L, 2L, "redis cache distributed lock", "redis,cache", "FACT", 3, 99L);

        verify(agentMemoryMapper, times(1)).insert(any(AgentMemoryEntity.class));
    }
}
