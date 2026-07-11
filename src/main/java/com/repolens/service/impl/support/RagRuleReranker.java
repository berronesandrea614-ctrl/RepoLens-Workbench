package com.repolens.service.impl.support;

import com.repolens.domain.enums.ChunkType;
import com.repolens.domain.vo.RagChunkVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * MVP 阶段规则 rerank 组件。
 * 设计目标：在不引入额外模型的前提下，利用查询意图对召回结果做轻量调序。
 * 边界说明：这里只做规则加权，不做语义重排模型。
 */
@Slf4j
@Component
public class RagRuleReranker {

    private static final float FILE_NAME_BONUS = 0.01f;
    private static final float API_BONUS = 0.008f;
    private static final float CONFIG_BONUS = 0.008f;
    private static final float CLASS_BONUS = 0.006f;
    private static final float METHOD_BONUS = 0.006f;
    private static final float CREATE_INTENT_BONUS = 0.01f;

    private static final Set<String> API_KEYWORDS = Set.of("接口", "api", "controller", "路径");
    private static final Set<String> CONFIG_KEYWORDS = Set.of("配置", "config", "yml", "yaml", "properties");
    private static final Set<String> CLASS_KEYWORDS = Set.of("类", "class", "职责");
    private static final Set<String> METHOD_KEYWORDS = Set.of("方法", "method", "调用");
    private static final Set<String> CREATE_KEYWORDS = Set.of("创建", "create", "新增", "post");

    /**
     * 根据 query 对候选 chunk 重排。
     *
     * @param query 用户查询
     * @param chunks 原始召回结果（已包含基础分 score）
     * @return rerank 后的结果，按分数从高到低排序
     */
    public List<RagChunkVO> rerank(String query, List<RagChunkVO> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        boolean preferApi = containsAny(normalizedQuery, API_KEYWORDS);
        boolean preferConfig = containsAny(normalizedQuery, CONFIG_KEYWORDS);
        boolean preferClass = containsAny(normalizedQuery, CLASS_KEYWORDS);
        boolean preferMethod = containsAny(normalizedQuery, METHOD_KEYWORDS);
        boolean preferCreate = containsAny(normalizedQuery, CREATE_KEYWORDS);

        List<RerankCandidate> candidates = new ArrayList<>(chunks.size());
        for (RagChunkVO chunk : chunks) {
            float baseScore = chunk.getScore() == null ? 0.0f : chunk.getScore();
            float bonus = 0.0f;

            if (StringUtils.hasText(chunk.getFilePath()) && containsFileName(normalizedQuery, chunk.getFilePath())) {
                bonus += FILE_NAME_BONUS;
            }

            ChunkType chunkType = parseChunkType(chunk.getChunkType());
            if (preferApi && chunkType == ChunkType.API) {
                bonus += API_BONUS;
            }
            if (preferConfig && chunkType == ChunkType.CONFIG) {
                bonus += CONFIG_BONUS;
            }
            if (preferClass && chunkType == ChunkType.CLASS) {
                bonus += CLASS_BONUS;
            }
            if (preferMethod && chunkType == ChunkType.METHOD) {
                bonus += METHOD_BONUS;
            }
            if (preferCreate && matchCreateIntent(chunk)) {
                bonus += CREATE_INTENT_BONUS;
            }

            candidates.add(new RerankCandidate(chunk, baseScore + bonus));
        }

        candidates.sort(Comparator.comparing(RerankCandidate::score).reversed());
        List<RagChunkVO> sorted = candidates.stream().map(RerankCandidate::chunk).toList();
        log.debug("RAG rerank completed, queryHash={}, size={}", query == null ? 0 : query.hashCode(), sorted.size());
        return sorted;
    }

    private boolean containsAny(String query, Set<String> keywords) {
        if (!StringUtils.hasText(query)) {
            return false;
        }
        for (String keyword : keywords) {
            if (query.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsFileName(String query, String filePath) {
        if (!StringUtils.hasText(query) || !StringUtils.hasText(filePath)) {
            return false;
        }
        String normalizedPath = filePath.toLowerCase(Locale.ROOT);
        String fileName = normalizedPath;
        int slash = normalizedPath.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < normalizedPath.length()) {
            fileName = normalizedPath.substring(slash + 1);
        }
        return query.contains(fileName) || normalizedPath.contains(query);
    }

    private ChunkType parseChunkType(String chunkType) {
        if (!StringUtils.hasText(chunkType)) {
            return null;
        }
        try {
            return ChunkType.valueOf(chunkType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static final java.util.regex.Pattern WORD_CREATE =
        java.util.regex.Pattern.compile("\\bcreate\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

    private boolean matchCreateIntent(RagChunkVO chunk) {
        String filePath = chunk.getFilePath() == null ? "" : chunk.getFilePath();
        String content = chunk.getContent() == null ? "" : chunk.getContent();
        String contentLower = content.toLowerCase(java.util.Locale.ROOT);
        // HTTP POST annotations are the most reliable Java REST CREATE-intent signal
        if (contentLower.contains("@postmapping") || contentLower.contains("httpmethod.post")) {
            return true;
        }
        // "create" as a whole word (word-boundary regex) — does NOT match "createElement" or "created"
        // because those have a letter immediately after "create" (no \b between 'e' and 'E'/'d')
        return WORD_CREATE.matcher(filePath).find() || WORD_CREATE.matcher(contentLower).find();
    }

    private record RerankCandidate(RagChunkVO chunk, float score) {
    }
}
