package com.repolens.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.repolens.domain.entity.AgentMemoryEntity;
import com.repolens.domain.vo.AgentMemoryVO;
import com.repolens.llm.LlmClient;
import com.repolens.llm.config.LlmRuntimeConfig;
import com.repolens.mapper.AgentMemoryMapper;
import com.repolens.service.AgentMemoryService;
import com.repolens.service.EmbeddingService;
import com.repolens.service.MemoryVectorService;
import com.repolens.service.impl.support.MemoryExtractor;
import com.repolens.service.impl.support.MemoryMetrics;
import com.repolens.service.impl.support.MemoryReconciler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentMemoryServiceImpl implements AgentMemoryService {

    /** 每 (user, repo) 保留的最大记忆条数。 */
    static final int MAX_NOTES = 30;

    /** 模糊去重阈值：Jaccard 相似度 >= 此值视为近似重复，在 reconcile 降级路径中跳过插入。 */
    static final double DEDUP_JACCARD = 0.8;

    /** 召回评分：时间衰减半衰期（天）。 */
    static final double HALF_LIFE_DAYS = 14.0;

    /** 关键词召回评分：相关性权重。 */
    static final double RELEVANCE_W = 0.7;

    /** 关键词召回评分：新鲜度（时间衰减）权重。 */
    static final double RECENCY_W = 0.3;

    /**
     * 融合分最低阈值：低于此值的记忆视为与当前问题无关，不注入 prompt。
     * 使用真实 COSINE 分后该阈值有实际意义（rank 代理分最低也为 0.6，旧代码该阈值是 no-op）。
     */
    static final double MIN_FUSION_SCORE = 0.35;

    /** 向量召回候选池大小（Milvus topK）。 */
    private static final int VECTOR_SEARCH_POOL = 8;

    /**
     * Reconcile 触发阈值：若最高候选相似度 >= 此值，调用 LLM 判定动作。
     */
    private static final double RECONCILE_SIMILARITY_THRESHOLD = 0.6;

    /**
     * Reconcile 候选池大小（取相似度最高的若干候选送给 LLM）。
     */
    private static final int RECONCILE_TOP_K = 3;

    private final AgentMemoryMapper agentMemoryMapper;
    private final EmbeddingService embeddingService;
    private final MemoryVectorService memoryVectorService;
    private final MemoryMetrics memoryMetrics;
    private final LlmClient llmClient;
    private final LlmRuntimeConfig llmRuntimeConfig;
    private final MemoryReconciler memoryReconciler;
    /**
     * F4: LLM reconcile 调用（最长 15 s）在事务外执行；TransactionTemplate 只包裹 DB 写操作，
     * 避免长事务持有数据库连接。使用 TransactionTemplate 而非 @Transactional + self-invocation
     * 以规避 Spring AOP 的 self-invocation 陷阱。
     */
    private final TransactionTemplate transactionTemplate;

    /** 当前时间供给器，默认取系统时钟；单测可覆盖以获得确定性。 */
    Supplier<LocalDateTime> clock = LocalDateTime::now;

    // ------------------------------------------------------------------
    // remember
    // ------------------------------------------------------------------

    /**
     * F4: @Transactional 已去除。Phase-1（loadAll + embedding + LLM reconcile）在事务外完成；
     * Phase-2（全部 DB 写操作）包裹在 TransactionTemplate.executeWithoutResult 内。
     */
    @Override
    public void remember(Long userId, Long repoId, String content, String keywords,
                         String memoryType, int importance, Long sessionId) {
        if (content == null || content.trim().isEmpty()) {
            return;
        }

        // ── Phase 1 (outside tx): reads + embedding + LLM decision ──────────
        List<AgentMemoryEntity> existing = loadAll(userId, repoId);

        // 构造 MemoryNote 供 reconciler LLM prompt 展示（纯内存，无 DB）。
        MemoryExtractor.MemoryNote newNote = new MemoryExtractor.MemoryNote(
                content, keywords,
                (memoryType != null && !memoryType.isBlank()) ? memoryType : "FACT",
                (importance >= 1 && importance <= 5) ? importance : 3);

        // 向量（降级关键词）找 top-3 候选。
        List<MemoryReconciler.ScoredMemory> candidates = findTopCandidates(
                userId, repoId, content, keywords, existing, RECONCILE_TOP_K);

        double maxSimilarity = candidates.isEmpty() ? 0.0 : candidates.get(0).similarity();

        // LLM reconcile 调用（慢，最长 15 s）在事务外，不持有 DB 连接。
        final MemoryReconciler.ReconcileResult llmResult =
                (maxSimilarity >= RECONCILE_SIMILARITY_THRESHOLD)
                ? memoryReconciler.reconcile(newNote, candidates)
                : null;  // null ⇒ 使用 Jaccard 降级路径

        // ── Phase 2 (inside tx): all DB writes ───────────────────────────────
        transactionTemplate.executeWithoutResult(tx -> {
            if (llmResult != null && !llmResult.isFallback()) {
                switch (llmResult.action()) {
                    case NOOP -> memoryMetrics.incrementReconcileNoop();
                    case ADD -> {
                        doInsert(userId, repoId, content, keywords, memoryType, importance, sessionId, existing);
                        memoryMetrics.incrementReconcileAdd();
                    }
                    case UPDATE -> {
                        doUpdate(llmResult.targetMemoryId(), userId, repoId,
                                 content, keywords, memoryType, importance, existing);
                        memoryMetrics.incrementReconcileUpdate();
                    }
                    case DELETE -> {
                        doDeleteAndInsert(llmResult.targetMemoryId(), userId, repoId, content, keywords,
                                memoryType, importance, sessionId, existing);
                        memoryMetrics.incrementReconcileDelete();
                    }
                    default -> jaccardFallbackInsert(userId, repoId, content, keywords,
                                                      memoryType, importance, sessionId, existing);
                }
            } else {
                // isFallback=true（LLM 调用失败/解析失败）或 maxSimilarity 不足：Jaccard 去重 + ADD。
                jaccardFallbackInsert(userId, repoId, content, keywords,
                                      memoryType, importance, sessionId, existing);
            }
        });
    }

    /** Jaccard 去重后插入（fallback 路径）。必须在 transactionTemplate 内调用。 */
    private void jaccardFallbackInsert(Long userId, Long repoId, String content, String keywords,
                                       String memoryType, int importance, Long sessionId,
                                       List<AgentMemoryEntity> existing) {
        Set<String> newTokens = tokenize(content);
        boolean duplicate = existing.stream()
                .anyMatch(e -> jaccard(newTokens, tokenize(e.getContent())) >= DEDUP_JACCARD);
        if (!duplicate) {
            doInsert(userId, repoId, content, keywords, memoryType, importance, sessionId, existing);
            memoryMetrics.incrementReconcileAdd();
        }
    }

    /** 插入新记忆并触发淘汰逻辑。 */
    private void doInsert(Long userId, Long repoId, String content, String keywords,
                          String memoryType, int importance, Long sessionId,
                          List<AgentMemoryEntity> existing) {
        String resolvedType = (memoryType != null && !memoryType.isBlank()) ? memoryType : "FACT";
        int resolvedImp = (importance >= 1 && importance <= 5) ? importance : 3;

        AgentMemoryEntity entity = new AgentMemoryEntity();
        entity.setUserId(userId);
        entity.setRepoId(repoId);
        entity.setContent(content);
        entity.setKeywords(keywords);
        entity.setSourceSessionId(sessionId);
        entity.setCreatedAt(clock.get());
        entity.setMemoryType(resolvedType);
        entity.setImportance((byte) resolvedImp);
        entity.setConfidence(BigDecimal.valueOf(0.80));
        entity.setAccessCount(0);
        agentMemoryMapper.insert(entity);

        upsertVectorSafe(entity.getId(), userId, repoId, content);
        evictIfNeeded(existing);
    }

    /**
     * 更新已有记忆的内容（reconcile UPDATE 路径）。
     * F3: 同步更新 keywords 字段，并对新内容重新 embed + upsert Milvus，
     * 防止向量与关键词索引停留在旧内容（stale vector/stale keywords）。
     */
    private void doUpdate(Long targetId, Long userId, Long repoId,
                          String newContent, String newKeywords, String newType, int newImportance,
                          List<AgentMemoryEntity> existing) {
        if (targetId == null) {
            log.warn("doUpdate: targetId is null, skipping update");
            return;
        }
        existing.stream()
                .filter(e -> targetId.equals(e.getId()))
                .findFirst()
                .ifPresent(target -> {
                    AgentMemoryEntity upd = new AgentMemoryEntity();
                    upd.setId(targetId);
                    upd.setContent(newContent);
                    // F3: 更新 keywords，否则关键词索引永久停留在旧内容
                    if (newKeywords != null) {
                        upd.setKeywords(newKeywords);
                    }
                    if (newType != null && !newType.isBlank()) {
                        upd.setMemoryType(newType);
                    }
                    int resolvedImp = (newImportance >= 1 && newImportance <= 5) ? newImportance : 3;
                    upd.setImportance((byte) resolvedImp);
                    upd.setConfidence(BigDecimal.valueOf(0.85));  // 被 LLM 确认，置信度略升
                    agentMemoryMapper.updateById(upd);
                    // F3: 对新内容重新 embed 并 upsert Milvus，防止向量与内容不一致
                    upsertVectorSafe(targetId, userId, repoId, newContent);
                });
    }

    /** 删除旧记忆并插入新记忆（reconcile DELETE 路径）。 */
    private void doDeleteAndInsert(Long targetId, Long userId, Long repoId, String content,
                                   String keywords, String memoryType, int importance,
                                   Long sessionId, List<AgentMemoryEntity> existing) {
        if (targetId != null) {
            agentMemoryMapper.deleteById(targetId);
            deleteVectorSafe(targetId);
            // 从 existing 列表移除已删的，以便 evictIfNeeded 计数正确
            existing.removeIf(e -> targetId.equals(e.getId()));
        }
        doInsert(userId, repoId, content, keywords, memoryType, importance, sessionId, existing);
    }

    /**
     * 获取 top-N 相似候选（向量优先，降级关键词 Jaccard）。
     * F2: 使用真实 COSINE 分（MemoryHit.score()），不再使用 rank 代理分。
     */
    private List<MemoryReconciler.ScoredMemory> findTopCandidates(
            Long userId, Long repoId, String content, String keywords,
            List<AgentMemoryEntity> existing, int topN) {
        // 先尝试向量路径
        try {
            float[] emb = embeddingService.embed(content);
            if (emb != null && emb.length > 0) {
                List<MemoryVectorService.MemoryHit> hits =
                        memoryVectorService.searchSimilar(emb, userId, repoId, topN);
                if (hits != null && !hits.isEmpty()) {
                    List<Long> idList = hits.stream()
                            .mapToLong(MemoryVectorService.MemoryHit::memoryId)
                            .boxed()
                            .collect(Collectors.toList());
                    List<AgentMemoryEntity> entities = loadByIds(userId, repoId, idList);
                    List<MemoryReconciler.ScoredMemory> scored = new ArrayList<>();
                    for (MemoryVectorService.MemoryHit h : hits) {
                        entities.stream()
                                .filter(e -> e.getId() != null && e.getId().longValue() == h.memoryId())
                                .findFirst()
                                .ifPresent(e -> scored.add(
                                        new MemoryReconciler.ScoredMemory(e, h.score())));
                    }
                    if (!scored.isEmpty()) {
                        return scored.stream()
                                .sorted(Comparator.comparingDouble(
                                        MemoryReconciler.ScoredMemory::similarity).reversed())
                                .limit(topN)
                                .collect(Collectors.toList());
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("findTopCandidates vector path failed, fallback to keyword, err={}", ex.getMessage());
        }

        // 降级：关键词 Jaccard 相似度
        Set<String> newTokens = tokenize(content + " " + keywords);
        return existing.stream()
                .map(e -> {
                    double sim = jaccard(newTokens, tokenize(e.getContent() + " " + e.getKeywords()));
                    return new MemoryReconciler.ScoredMemory(e, sim);
                })
                .filter(sm -> sm.similarity() > 0)
                .sorted(Comparator.comparingDouble(MemoryReconciler.ScoredMemory::similarity).reversed())
                .limit(topN)
                .collect(Collectors.toList());
    }

    /**
     * 淘汰策略（E3）：超出 MAX_NOTES 时，按综合分最低者出局。
     * 综合分 = importance*0.4 + recency*0.4 + accessCountNormalized*0.2
     */
    private void evictIfNeeded(List<AgentMemoryEntity> existing) {
        int overflow = existing.size() + 1 - MAX_NOTES;
        if (overflow <= 0) {
            return;
        }
        LocalDateTime now = clock.get();
        // 计算 access_count 最大值用于归一化
        int maxAccess = existing.stream()
                .mapToInt(e -> e.getAccessCount() != null ? e.getAccessCount() : 0)
                .max().orElse(1);
        int normalizer = Math.max(1, maxAccess);

        existing.stream()
                .sorted(Comparator.comparingDouble(
                        (AgentMemoryEntity e) -> evictionScore(e, now, normalizer)))
                // 最低分先淘汰
                .limit(overflow)
                .forEach(e -> {
                    agentMemoryMapper.deleteById(e.getId());
                    deleteVectorSafe(e.getId());
                });
    }

    /**
     * 淘汰综合分（越高越优先保留，越低越先被淘汰）：
     * importance*0.4 + recency*0.4 + accessCountNormalized*0.2
     */
    private double evictionScore(AgentMemoryEntity e, LocalDateTime now, int normalizer) {
        double imp = (e.getImportance() != null ? (double) e.getImportance() : 3.0) / 5.0;
        double rec = recency(e.getCreatedAt(), now);
        double acc = (e.getAccessCount() != null ? (double) e.getAccessCount() : 0.0) / normalizer;
        return imp * 0.4 + rec * 0.4 + acc * 0.2;
    }

    // ------------------------------------------------------------------
    // recall
    // ------------------------------------------------------------------

    @Override
    public List<AgentMemoryEntity> recall(Long userId, Long repoId, String queryKeywords, int topK) {
        if (topK <= 0) {
            return List.of();
        }

        LocalDateTime now = clock.get();
        Set<String> query = tokenize(queryKeywords);

        // 向量路径：尝试 embed 查询 → Milvus 检索 → 融合分 → 阈值过滤。
        // null 表示向量路径不可用（Milvus 宕机/embed 失败/Milvus 索引为空），应降级到关键词路径。
        List<AgentMemoryEntity> vectorResult = tryVectorRecall(userId, repoId, query, queryKeywords, topK, now);
        if (vectorResult != null) {
            // F7: 仅在结果非空时计入 vectorHits，避免阈值全过滤后仍计数的误报
            if (!vectorResult.isEmpty()) {
                memoryMetrics.incrementVectorHits();
            }
            updateAccessSafe(vectorResult, now);
            return vectorResult;
        }

        // 降级：关键词路径（Milvus 不可用 / embedding 失败 / Milvus 索引为空）。
        memoryMetrics.incrementVectorFallbacks();
        List<AgentMemoryEntity> kwResult = keywordRecall(userId, repoId, query, topK, now);
        updateAccessSafe(kwResult, now);
        return kwResult;
    }

    /**
     * 向量召回路径。
     * 返回 null 表示向量路径不可用（embed 失败 / Milvus 异常 / Milvus 索引为空），调用方应降级。
     * 返回空列表表示向量路径正常但阈值过滤后无匹配（不再降级到关键词）。
     *
     * F2: 使用 MemoryHit.score()（真实 COSINE 分）而非 rank 代理分；
     *     threshold=0.35 现在对真实分有实际意义。
     * F6: Milvus 返回空候选（索引可能为空）时返回 null，触发关键词降级，
     *     而非错误地返回 List.of() 跳过关键词路径。
     */
    private List<AgentMemoryEntity> tryVectorRecall(Long userId, Long repoId,
                                                     Set<String> query, String queryKeywords,
                                                     int topK, LocalDateTime now) {
        try {
            float[] qVec = embeddingService.embed(queryKeywords);
            if (qVec == null || qVec.length == 0) {
                return null;
            }

            List<MemoryVectorService.MemoryHit> candidateHits =
                    memoryVectorService.searchSimilar(qVec, userId, repoId, VECTOR_SEARCH_POOL);

            // F6: Milvus 索引为空或无命中 → 落到关键词路径，而非返回空列表假装向量路径成功
            if (candidateHits == null || candidateHits.isEmpty()) {
                return null;
            }

            List<Long> candidateIds = candidateHits.stream()
                    .mapToLong(MemoryVectorService.MemoryHit::memoryId)
                    .boxed()
                    .collect(Collectors.toList());
            List<AgentMemoryEntity> candidates = loadByIds(userId, repoId, candidateIds);

            // F2: 使用真实 COSINE 分建 scoreMap，在 fusionScore 中替换 rank 代理
            Map<Long, Float> scoreMap = new HashMap<>();
            for (MemoryVectorService.MemoryHit h : candidateHits) {
                scoreMap.put(h.memoryId(), h.score());
            }

            return candidates.stream()
                    .filter(e -> fusionScore(e, scoreMap, query, now) >= MIN_FUSION_SCORE)
                    .sorted(Comparator.comparingDouble((AgentMemoryEntity e) ->
                                    fusionScore(e, scoreMap, query, now)).reversed())
                    .limit(topK)
                    .collect(Collectors.toList());
        } catch (Exception ex) {
            log.warn("vector recall failed, fallback to keyword, repoId={}, err={}", repoId, ex.getMessage());
            return null;
        }
    }

    /**
     * 融合分：cosine*0.6 + kwOverlap*0.2 + timeDecay*0.1 + importance/5*0.1。
     * F2: cosine 来自 Milvus 真实 COSINE 分（scoreMap），不再使用 rank 代理。
     */
    private double fusionScore(AgentMemoryEntity e, Map<Long, Float> scoreMap,
                                Set<String> query, LocalDateTime now) {
        Float cosine = scoreMap.get(e.getId());
        double cosineVal = (cosine != null) ? cosine.doubleValue() : 0.0;
        double kwOverlap = overlap(query, tokenize(e.getKeywords())) / (double) Math.max(1, query.size());
        double timeDecay = recency(e.getCreatedAt(), now);
        double imp = (e.getImportance() != null ? (double) e.getImportance() : 3.0) / 5.0;
        return cosineVal * 0.6 + kwOverlap * 0.2 + timeDecay * 0.1 + imp * 0.1;
    }

    /**
     * 关键词召回路径，应用 MIN_FUSION_SCORE 阈值。
     */
    private List<AgentMemoryEntity> keywordRecall(Long userId, Long repoId,
                                                   Set<String> query, int topK,
                                                   LocalDateTime now) {
        List<AgentMemoryEntity> all = loadAll(userId, repoId);
        return all.stream()
                .filter(e -> keywordScore(query, e, now) >= MIN_FUSION_SCORE)
                .sorted(Comparator
                        .comparingDouble((AgentMemoryEntity e) -> keywordScore(query, e, now))
                        .reversed()
                        .thenComparing(Comparator.comparing(AgentMemoryEntity::getCreatedAt,
                                Comparator.nullsFirst(Comparator.naturalOrder())).reversed()))
                .limit(topK)
                .collect(Collectors.toList());
    }

    private double keywordScore(Set<String> query, AgentMemoryEntity note, LocalDateTime now) {
        int matched = overlap(query, tokenize(note.getKeywords()));
        double relevance = matched / (double) Math.max(1, query.size());
        double rec = recency(note.getCreatedAt(), now);
        return relevance * RELEVANCE_W + rec * RECENCY_W;
    }

    private void updateAccessSafe(List<AgentMemoryEntity> recalled, LocalDateTime now) {
        if (recalled == null || recalled.isEmpty()) {
            return;
        }
        for (AgentMemoryEntity e : recalled) {
            try {
                AgentMemoryEntity upd = new AgentMemoryEntity();
                upd.setId(e.getId());
                upd.setLastAccessedAt(now);
                upd.setAccessCount((e.getAccessCount() != null ? e.getAccessCount() : 0) + 1);
                agentMemoryMapper.updateById(upd);
            } catch (Exception ex) {
                log.warn("updateAccessSafe failed for memoryId={}, err={}", e.getId(), ex.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------
    // list / forget
    // ------------------------------------------------------------------

    @Override
    public List<AgentMemoryVO> list(Long userId, Long repoId) {
        return loadAll(userId, repoId).stream()
                .sorted(Comparator.comparing(AgentMemoryEntity::getCreatedAt,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(AgentMemoryEntity::getId,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .reversed())
                .map(e -> AgentMemoryVO.builder()
                        .id(e.getId())
                        .content(e.getContent())
                        .keywords(e.getKeywords())
                        .createdAt(e.getCreatedAt())
                        .memoryType(e.getMemoryType())
                        .importance(e.getImportance() != null ? (int) e.getImportance() : null)
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void forget(Long userId, Long repoId, Long memoryId) {
        if (memoryId == null) {
            return;
        }
        agentMemoryMapper.delete(Wrappers.<AgentMemoryEntity>lambdaQuery()
                .eq(AgentMemoryEntity::getId, memoryId)
                .eq(AgentMemoryEntity::getUserId, userId)
                .eq(AgentMemoryEntity::getRepoId, repoId));
        deleteVectorSafe(memoryId);
    }

    // ------------------------------------------------------------------
    // Vector helpers (fail-safe)
    // ------------------------------------------------------------------

    private void upsertVectorSafe(Long memoryId, Long userId, Long repoId, String content) {
        try {
            float[] emb = embeddingService.embed(content);
            if (emb != null && emb.length > 0) {
                memoryVectorService.upsertMemoryVector(memoryId, userId, repoId, emb);
            }
        } catch (Exception ex) {
            log.warn("upsertVectorSafe failed for memoryId={}, err={}", memoryId, ex.getMessage());
        }
    }

    private void deleteVectorSafe(Long memoryId) {
        try {
            memoryVectorService.deleteMemoryVector(memoryId);
        } catch (Exception ex) {
            log.warn("deleteVectorSafe failed for memoryId={}, err={}", memoryId, ex.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private List<AgentMemoryEntity> loadAll(Long userId, Long repoId) {
        return agentMemoryMapper.selectList(Wrappers.<AgentMemoryEntity>lambdaQuery()
                .eq(AgentMemoryEntity::getUserId, userId)
                .eq(AgentMemoryEntity::getRepoId, repoId));
    }

    private List<AgentMemoryEntity> loadByIds(Long userId, Long repoId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return agentMemoryMapper.selectList(Wrappers.<AgentMemoryEntity>lambdaQuery()
                .eq(AgentMemoryEntity::getUserId, userId)
                .eq(AgentMemoryEntity::getRepoId, repoId)
                .in(AgentMemoryEntity::getId, ids));
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        int inter = intersection.size();
        int union = a.size() + b.size() - inter;
        return union == 0 ? 0.0 : inter / (double) union;
    }

    private static Set<String> tokenize(String keywords) {
        Set<String> tokens = new LinkedHashSet<>();
        if (keywords == null || keywords.isEmpty()) {
            return tokens;
        }
        String lower = keywords.toLowerCase();
        for (String part : lower.split("[^a-z0-9_]+")) {
            if (!part.isEmpty()) {
                tokens.add(part);
            }
        }
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c >= '一' && c <= '鿿') {
                tokens.add(String.valueOf(c));
            }
        }
        return tokens;
    }

    private static int overlap(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        return intersection.size();
    }

    private double recency(LocalDateTime createdAt, LocalDateTime now) {
        if (createdAt == null) {
            return 0.0;
        }
        double ageDays = Math.max(0.0, Duration.between(createdAt, now).getSeconds() / 86400.0);
        return Math.exp(-ageDays / HALF_LIFE_DAYS);
    }
}
