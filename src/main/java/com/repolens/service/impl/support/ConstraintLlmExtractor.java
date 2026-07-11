package com.repolens.service.impl.support;

import java.util.List;

/**
 * Optional LLM-based constraint extraction hook (Feature B P2).
 * Provides a fallback for rule sentences not classified by the deterministic regex.
 *
 * <p>Implementations should be failure-safe (return empty list on error).
 * If no implementation bean is registered, {@link ConstraintRuleCacheService} skips LLM extraction.
 * A real implementation would call DeepSeek/Claude with structured output.
 */
public interface ConstraintLlmExtractor {

    /**
     * Attempts to classify ambiguous rule sentences using an LLM.
     * Must be failure-safe: return empty list on any error.
     *
     * @param ambiguousSentences sentences not classified by deterministic regex
     * @return additional ConstraintRules extracted by LLM; never null
     */
    List<ConstraintRule> extract(List<String> ambiguousSentences);
}
