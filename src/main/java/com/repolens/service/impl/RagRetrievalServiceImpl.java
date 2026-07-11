package com.repolens.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.CodeChunkEntity;
import com.repolens.domain.entity.RepoEntity;
import com.repolens.domain.enums.ChunkType;
import com.repolens.domain.vo.RagChunkVO;
import com.repolens.domain.vo.RagSearchResultVO;
import com.repolens.domain.vo.VectorSearchHitVO;
import com.repolens.mapper.CodeChunkMapper;
import com.repolens.mapper.RepoMapper;
import com.repolens.security.PermissionService;
import com.repolens.service.MilvusService;
import com.repolens.service.RagRetrievalService;
import com.repolens.service.impl.support.RagRuleReranker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * RepoLens 第七阶段 RAG 检索服务。
 * 责任：
 * 1) 调用 Milvus 完成向量召回；
 * 2) 回查 MySQL 组装 chunk 内容；
 * 3) 执行规则 rerank；
 * 4) Milvus 异常时降级到 MySQL 关键词检索。
 * 边界：本阶段仅返回证据，不负责 LLM 生成回答。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagRetrievalServiceImpl implements RagRetrievalService {

    private static final int DEFAULT_TOP_K = 8;
    private static final int MAX_TOP_K = 20;
    private static final int CONTENT_MAX_LEN = 4000;
    private static final int PREVIEW_MAX_LEN = 280;
    private static final int MAX_KEYWORD_TERMS = 6;
    private static final Set<String> STOPWORDS = Set.of(
            "如何", "什么", "怎么", "怎样", "为何", "如果", "以及", "并且",
            "的", "了", "吗", "呢", "和", "与", "或", "并");
    private static final Set<String> API_HINTS = Set.of("接口", "api", "controller", "路径");
    private static final Set<String> CONFIG_HINTS = Set.of("配置", "config", "yml", "yaml", "properties");
    private static final Set<String> CLASS_HINTS = Set.of("类", "class", "职责");
    private static final Set<String> METHOD_HINTS = Set.of("方法", "method", "调用");

    private final RepoMapper repoMapper;
    private final CodeChunkMapper codeChunkMapper;
    private final PermissionService permissionService;
    private final MilvusService milvusService;
    private final RagRuleReranker ragRuleReranker;

    /**
     * RAG 检索主入口。
     * 输入：repoId/userId/query/topK
     * 输出：包含命中 chunk 与降级标记的结果对象
     * 异常：repo 不存在/权限不足时抛业务异常；Milvus 异常不抛出，转为 MySQL 降级。
     */
    @Override
    public RagSearchResultVO retrieve(Long repoId, Long userId, String query, Integer topK) {
        validateRequest(repoId, userId, query);
        int limit = normalizeTopK(topK);

        try {
            List<VectorSearchHitVO> hits = milvusService.search(query, repoId, limit);
            List<RagChunkVO> vectorChunks = buildRagChunksFromVectorHits(repoId, hits, limit);
            List<RagChunkVO> keywordChunks = fallbackKeywordSearch(repoId, query, limit);
            List<RagChunkVO> mergedCandidates = mergeCandidates(vectorChunks, keywordChunks, limit);
            List<RagChunkVO> reranked = ragRuleReranker.rerank(query, mergedCandidates).stream()
                    .limit(limit)
                    .toList();
            return RagSearchResultVO.builder()
                    .repoId(repoId)
                    .query(query)
                    .topK(limit)
                    .hitCount(reranked.size())
                    .degraded(false)
                    .degradeReason(null)
                    .results(reranked)
                    .build();
        } catch (Exception ex) {
            // Milvus 异常降级：保证检索接口仍可返回关键词证据。
            String reason = "vector search failed: " + trimError(ex.getMessage());
            log.warn("RAG vector retrieval degraded, repoId={}, topK={}, reason={}", repoId, limit, reason);
            List<RagChunkVO> degradedChunks = fallbackKeywordSearch(repoId, query, limit);
            List<RagChunkVO> reranked = ragRuleReranker.rerank(query, degradedChunks).stream()
                    .limit(limit)
                    .toList();
            return RagSearchResultVO.builder()
                    .repoId(repoId)
                    .query(query)
                    .topK(limit)
                    .hitCount(reranked.size())
                    .degraded(true)
                    .degradeReason(reason)
                    .results(reranked)
                    .build();
        }
    }

    /**
     * RRF (Reciprocal Rank Fusion, k=60) 融合两路召回结果，替代直接分数混排。
     * 公式：score = Σ 1/(k+rank)，然后归一化到 0~1 写入 VO.score。
     * 好处：消除向量分(cosine 0~1)与关键词启发分(0.1~0.9)的量纲不一致问题。
     */
    private List<RagChunkVO> mergeCandidates(List<RagChunkVO> vectorChunks,
                                              List<RagChunkVO> keywordChunks, int topK) {
        final int K = 60;
        Map<String, Float> rrfScores = new LinkedHashMap<>();
        // Collect unique chunks preserving first-seen order (vector first, then keyword)
        Map<String, RagChunkVO> chunkById = new LinkedHashMap<>();

        for (int i = 0; i < vectorChunks.size(); i++) {
            RagChunkVO c = vectorChunks.get(i);
            if (!StringUtils.hasText(c.getChunkId())) continue;
            rrfScores.merge(c.getChunkId(), 1.0f / (K + i + 1), Float::sum);
            chunkById.putIfAbsent(c.getChunkId(), c);
        }
        for (int i = 0; i < keywordChunks.size(); i++) {
            RagChunkVO c = keywordChunks.get(i);
            if (!StringUtils.hasText(c.getChunkId())) continue;
            rrfScores.merge(c.getChunkId(), 1.0f / (K + i + 1), Float::sum);
            chunkById.putIfAbsent(c.getChunkId(), c);
        }
        if (rrfScores.isEmpty()) return List.of();

        float maxRrf = rrfScores.values().stream().max(Float::compare).orElse(1.0f);
        if (maxRrf <= 0) maxRrf = 1.0f;
        final float normalizer = maxRrf;

        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Float>comparingByValue().reversed())
                .limit((long) topK * 2)
                .map(e -> {
                    RagChunkVO orig = chunkById.get(e.getKey());
                    if (orig == null) return null;
                    // Write normalized RRF score back (0~1 range, avoids frontend display breakage)
                    orig.setScore(e.getValue() / normalizer);
                    return orig;
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private void validateRequest(Long repoId, Long userId, String query) {
        RepoEntity repo = repoMapper.selectById(repoId);
        if (repo == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "Repo not found");
        }
        if (!permissionService.checkRepoPermission(userId, repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "No repo permission");
        }
        if (!StringUtils.hasText(query)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Query cannot be empty");
        }
    }

    private List<RagChunkVO> buildRagChunksFromVectorHits(Long repoId, List<VectorSearchHitVO> hits, int topK) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<String> chunkIds = hits.stream()
                .map(VectorSearchHitVO::getChunkId)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        if (chunkIds.isEmpty()) {
            return List.of();
        }

        List<CodeChunkEntity> chunks = codeChunkMapper.selectList(Wrappers.<CodeChunkEntity>lambdaQuery()
                .in(CodeChunkEntity::getChunkId, chunkIds));
        Map<String, CodeChunkEntity> chunkMap = new HashMap<>();
        for (CodeChunkEntity chunk : chunks) {
            chunkMap.put(chunk.getChunkId(), chunk);
        }

        List<RagChunkVO> results = new ArrayList<>();
        for (VectorSearchHitVO hit : hits) {
            CodeChunkEntity chunk = chunkMap.get(hit.getChunkId());
            if (chunk == null) {
                // MySQL 是事实源。Milvus 命中但 MySQL 已无该 chunk 时，直接跳过，避免旧向量残留把检索接口打崩。
                log.warn("Skip stale Milvus hit because code_chunk is missing, repoId={}, chunkId={}",
                        repoId, hit.getChunkId());
                continue;
            }
            results.add(toRagChunkVO(chunk, hit.getScore()));
            if (results.size() >= topK) {
                break;
            }
        }
        return results;
    }

    /**
     * Milvus 不可用时的 MySQL 降级检索。
     * 语义能力会下降，但可保证系统仍可返回可追溯证据。
     */
    private List<RagChunkVO> fallbackKeywordSearch(Long repoId, String query, int topK) {
        List<String> terms = extractSearchTerms(query);
        if (terms.isEmpty()) {
            return List.of();
        }

        // 多词 OR 召回：任一词命中 content 或 filePath 即为候选，避免整句只用首词退化匹配。
        var wrapper = Wrappers.<CodeChunkEntity>lambdaQuery()
                .eq(CodeChunkEntity::getRepoId, repoId)
                .and(w -> {
                    for (int i = 0; i < terms.size(); i++) {
                        if (i > 0) {
                            w.or();
                        }
                        String term = terms.get(i);
                        w.like(CodeChunkEntity::getContent, term)
                                .or()
                                .like(CodeChunkEntity::getFilePath, term);
                    }
                });
        List<ChunkType> hintedChunkTypes = resolveChunkTypeHints(query);
        if (!hintedChunkTypes.isEmpty()) {
            wrapper.in(CodeChunkEntity::getChunkType, hintedChunkTypes);
        }

        // 多召回一些候选（命中更多词的可能排在数据库自然序之后），再靠打分把多词命中提上来。
        wrapper.orderByDesc(CodeChunkEntity::getUpdatedAt)
                .orderByAsc(CodeChunkEntity::getId)
                .last("LIMIT " + Math.max(topK * 2, topK));

        List<CodeChunkEntity> chunks = codeChunkMapper.selectList(wrapper);
        if (chunks.isEmpty()) {
            return List.of();
        }

        // 关键词模式下构造基础分：命中的不同词越多分越高，后续再经过统一 rerank 流程。
        Map<String, Float> scores = new LinkedHashMap<>();
        for (CodeChunkEntity chunk : chunks) {
            scores.put(chunk.getChunkId(), multiTermScore(terms, chunk));
        }

        // Normalize degraded keyword scores to [0, 1] for display consistency
        float maxScore = scores.values().stream().max(Float::compare).orElse(1.0f);
        if (maxScore <= 0) maxScore = 1.0f;
        final float normalizer = maxScore;
        scores.replaceAll((k, v) -> v / normalizer);

        return chunks.stream()
                .map(chunk -> toRagChunkVO(chunk, scores.getOrDefault(chunk.getChunkId(), 0.0f)))
                .sorted(Comparator.comparing((RagChunkVO vo) -> vo.getScore() == null ? 0.0f : vo.getScore()).reversed())
                .limit(topK)
                .toList();
    }

    /**
     * 把 code_chunk 事实记录转换成对外暴露的检索结果对象，
     * 同时裁剪 content / preview，避免把过长源码直接返回给前端。
     */
    private RagChunkVO toRagChunkVO(CodeChunkEntity chunk, Float score) {
        String content = safeContent(chunk.getContent());
        return RagChunkVO.builder()
                .chunkId(chunk.getChunkId())
                .filePath(chunk.getFilePath())
                .chunkType(chunk.getChunkType() == null ? null : chunk.getChunkType().name())
                .language(chunk.getLanguage())
                .startLine(chunk.getStartLine())
                .endLine(chunk.getEndLine())
                .score(score)
                .content(content)
                .contentPreview(buildPreview(content))
                .build();
    }

    private int normalizeTopK(Integer topK) {
        if (topK == null || topK <= 0) {
            return DEFAULT_TOP_K;
        }
        return Math.min(topK, MAX_TOP_K);
    }

    private String safeContent(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        return content.length() > CONTENT_MAX_LEN ? content.substring(0, CONTENT_MAX_LEN) : content;
    }

    private String buildPreview(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        return content.length() > PREVIEW_MAX_LEN ? content.substring(0, PREVIEW_MAX_LEN) : content;
    }

    /**
     * 把整句 query 拆成多个有意义的检索词：
     * - ASCII/数字词：长度 >= 2；
     * - CJK：按二元切分（bigram），比单字更有区分度；
     * 并剔除明显停用词，最多保留 MAX_KEYWORD_TERMS 个词用于 LIKE 召回。
     */
    private List<String> extractSearchTerms(String query) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        String lower = query.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> terms = new LinkedHashSet<>();

        for (String part : lower.split("[^a-z0-9_]+")) {
            if (part.length() >= 2 && !STOPWORDS.contains(part)) {
                terms.add(part);
            }
        }

        StringBuilder cjkRun = new StringBuilder();
        for (int i = 0; i <= lower.length(); i++) {
            char c = i < lower.length() ? lower.charAt(i) : '\0';
            if (isCjk(c)) {
                cjkRun.append(c);
            } else {
                addCjkTerms(cjkRun.toString(), terms);
                cjkRun.setLength(0);
            }
        }

        return terms.stream().limit(MAX_KEYWORD_TERMS).toList();
    }

    private void addCjkTerms(String run, LinkedHashSet<String> terms) {
        if (run.isEmpty()) {
            return;
        }
        if (run.length() == 1) {
            if (!STOPWORDS.contains(run)) {
                terms.add(run);
            }
            return;
        }
        for (int i = 0; i + 1 < run.length(); i++) {
            String bigram = run.substring(i, i + 2);
            if (!STOPWORDS.contains(bigram)) {
                terms.add(bigram);
            }
        }
    }

    private boolean isCjk(char c) {
        return c >= '一' && c <= '鿿';
    }

    /**
     * 根据问题里的语义提示词，提前偏向 API / CONFIG / CLASS / METHOD 类型的 chunk。
     * 这是非常轻量的领域规则，用来弥补纯向量检索在代码场景下的偏差。
     */
    private List<ChunkType> resolveChunkTypeHints(String query) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        String normalized = query.toLowerCase(Locale.ROOT);
        List<ChunkType> hints = new ArrayList<>();
        if (containsAny(normalized, API_HINTS)) {
            hints.add(ChunkType.API);
        }
        if (containsAny(normalized, CONFIG_HINTS)) {
            hints.add(ChunkType.CONFIG);
        }
        if (containsAny(normalized, CLASS_HINTS)) {
            hints.add(ChunkType.CLASS);
        }
        if (containsAny(normalized, METHOD_HINTS)) {
            hints.add(ChunkType.METHOD);
        }
        return hints;
    }

    private boolean containsAny(String query, Set<String> keywords) {
        for (String keyword : keywords) {
            if (query.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * MySQL 降级检索没有真实向量分数，这里按“命中的不同检索词数量”构造启发式分值，
     * 保证多词命中的 chunk 排在只命中单个词的 chunk 之前。
     */
    private float multiTermScore(List<String> terms, CodeChunkEntity chunk) {
        String filePath = chunk.getFilePath() == null ? "" : chunk.getFilePath().toLowerCase(Locale.ROOT);
        String content = chunk.getContent() == null ? "" : chunk.getContent().toLowerCase(Locale.ROOT);
        float score = 0.1f;
        int matchedTerms = 0;
        for (String term : terms) {
            boolean inContent = content.contains(term);
            boolean inPath = filePath.contains(term);
            if (inContent || inPath) {
                matchedTerms++;
            }
            if (inPath) {
                score += 0.15f;
            }
            if (inContent) {
                score += 0.1f;
            }
        }
        // 不同词命中数是主导项：命中越多，得分越高。
        score += matchedTerms * 0.2f;
        return score;
    }

    private String trimError(String errorMsg) {
        if (!StringUtils.hasText(errorMsg)) {
            return "unknown";
        }
        String trimmed = errorMsg.trim();
        return trimmed.length() > 300 ? trimmed.substring(0, 300) : trimmed;
    }
}
