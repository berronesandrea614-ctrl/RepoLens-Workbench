package com.repolens.service.impl.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.repolens.domain.entity.RepoConstraintRuleEntity;
import com.repolens.mapper.RepoConstraintRuleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Manages cached parsed constraint rules in {@code repo_constraint_rule} table (Feature B P2).
 *
 * <p>Cache key: (repo_id, source_hash). source_hash = SHA-256 of the AGENTS.md content.
 * On cache hit (same hash) → return cached rules directly.
 * On cache miss → delete old rules for this repo, parse + persist new ones, return.
 *
 * <p>Optionally calls {@link ConstraintLlmExtractor} for ambiguous sentences not classified
 * by regex. If no LLM extractor bean is registered, only regex-classified rules are returned.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConstraintRuleCacheService {

    private final RepoConstraintRuleMapper ruleMapper;

    /** Optional LLM extractor — null if no bean is registered. */
    @Nullable
    @Autowired(required = false)
    private ConstraintLlmExtractor llmExtractor;

    /**
     * Returns parsed constraint rules for the given repo and rules text.
     * Uses the DB cache keyed by (repo_id, source_hash); re-parses only when content changes.
     * Failure-safe: any exception returns an empty list.
     *
     * @param repoId    repository ID
     * @param rulesText raw content of AGENTS.md / .repolens/rules.md
     * @return list of constraint rules; empty if parse fails or rulesText is blank
     */
    public List<ConstraintRule> loadOrParse(Long repoId, String rulesText) {
        if (!StringUtils.hasText(rulesText)) return List.of();
        try {
            return doLoadOrParse(repoId, rulesText);
        } catch (Exception ex) {
            log.warn("constraint rule cache failed, repoId={}, err={}", repoId, ex.getMessage());
            return List.of();
        }
    }

    // ── Core logic ────────────────────────────────────────────────────────────

    private List<ConstraintRule> doLoadOrParse(Long repoId, String rulesText) {
        String hash = sha256(rulesText);

        // ── Cache hit ─────────────────────────────────────────────────────────
        List<RepoConstraintRuleEntity> cached = ruleMapper.selectList(
                Wrappers.<RepoConstraintRuleEntity>lambdaQuery()
                        .eq(RepoConstraintRuleEntity::getRepoId, repoId)
                        .eq(RepoConstraintRuleEntity::getSourceHash, hash));

        if (cached != null && !cached.isEmpty()) {
            log.debug("constraint rules cache hit, repoId={}, hash={}, rules={}", repoId, hash, cached.size());
            return toConstraintRules(cached);
        }

        // ── Cache miss: re-parse and persist ─────────────────────────────────
        log.debug("constraint rules cache miss, repoId={}, parsing rules", repoId);

        // 1. Parse deterministic regex rules
        List<ConstraintRule> parsed = new ArrayList<>(ConstraintRuleParser.parseRules(rulesText));

        // 2. Optional LLM extraction for unclassified sentences
        if (llmExtractor != null) {
            List<String> ambiguous = collectAmbiguous(rulesText, parsed);
            if (!ambiguous.isEmpty()) {
                try {
                    List<ConstraintRule> llmRules = llmExtractor.extract(ambiguous);
                    if (llmRules != null) parsed.addAll(llmRules);
                } catch (Exception ex) {
                    log.warn("LLM constraint extraction failed, using regex-only. err={}", ex.getMessage());
                }
            }
        }

        // 3. Delete stale rules for this repo (different source_hash)
        try {
            ruleMapper.delete(
                    Wrappers.<RepoConstraintRuleEntity>lambdaQuery()
                            .eq(RepoConstraintRuleEntity::getRepoId, repoId));
        } catch (Exception ex) {
            log.warn("delete stale constraint rules failed, repoId={}, err={}", repoId, ex.getMessage());
        }

        // 4. Persist new rules
        LocalDateTime now = LocalDateTime.now();
        for (ConstraintRule rule : parsed) {
            try {
                RepoConstraintRuleEntity entity = new RepoConstraintRuleEntity();
                entity.setRepoId(repoId);
                entity.setSourceHash(hash);
                entity.setRuleType(rule.type());
                entity.setPattern(rule.pattern());
                entity.setRawText(rule.rawText());
                entity.setCheckable(rule.checkable());
                entity.setSeverity(rule.severity());
                entity.setCreatedAt(now);
                ruleMapper.insert(entity);
            } catch (Exception ex) {
                log.warn("insert constraint rule failed, rule='{}', err={}", rule.rawText(), ex.getMessage());
            }
        }

        return parsed;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Convert DB entities back to ConstraintRule records. */
    private static List<ConstraintRule> toConstraintRules(List<RepoConstraintRuleEntity> entities) {
        List<ConstraintRule> result = new ArrayList<>();
        for (RepoConstraintRuleEntity e : entities) {
            result.add(new ConstraintRule(
                    e.getRuleType(), e.getPattern(), e.getRawText(),
                    Boolean.TRUE.equals(e.getCheckable()),
                    e.getSeverity()));
        }
        return result;
    }

    /**
     * Collects non-blank, non-header rule lines that were not classified by the regex parser.
     */
    private static List<String> collectAmbiguous(String rulesText, List<ConstraintRule> parsed) {
        Set<String> parsedTexts = new HashSet<>();
        for (ConstraintRule r : parsed) {
            if (r.rawText() != null) parsedTexts.add(r.rawText());
        }

        List<String> ambiguous = new ArrayList<>();
        for (String line : rulesText.split("\n")) {
            String trimmed = line.trim().replaceFirst("^[-*+>]\\s+", "").trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            if (!parsedTexts.contains(trimmed)) {
                ambiguous.add(trimmed);
            }
        }
        return ambiguous;
    }

    /** Computes SHA-256 hex digest of the given text. */
    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            // Should never happen — SHA-256 is always available in Java
            return Integer.toHexString(text.hashCode());
        }
    }
}
