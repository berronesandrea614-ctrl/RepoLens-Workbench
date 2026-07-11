package com.repolens.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.AdrEntity;
import com.repolens.domain.entity.AgentRunPlanEntity;
import com.repolens.domain.entity.FileChangeLogEntity;
import com.repolens.domain.entity.RepoEntity;
import com.repolens.domain.entity.RequirementEntity;
import com.repolens.domain.vo.AdrVO;
import com.repolens.mapper.AdrMapper;
import com.repolens.mapper.AgentRunPlanMapper;
import com.repolens.mapper.FileChangeLogMapper;
import com.repolens.mapper.RepoMapper;
import com.repolens.mapper.RequirementMapper;
import com.repolens.service.AdrService;
import com.repolens.service.impl.support.AdrCrystallizer;
import com.repolens.service.impl.support.AdrCrystallizer.AdrDraft;
import com.repolens.service.impl.support.AdrCrystallizer.CrystallizeInput;
import com.repolens.service.impl.support.AdrCrystallizer.StepNote;
import com.repolens.service.impl.support.AdrSupersedeChecker;
import com.repolens.service.support.RepoWorkspaceResolver;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * ADR orchestration service implementation (Task 3).
 *
 * <p>Fail-safe: all public methods catch non-BizException errors internally
 * where noted; BizExceptions propagate to the caller.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdrServiceImpl implements AdrService {

    private final AdrMapper adrMapper;
    private final RequirementMapper requirementMapper;
    private final AgentRunPlanMapper agentRunPlanMapper;
    private final FileChangeLogMapper fileChangeLogMapper;
    private final RepoMapper repoMapper;
    private final AdrCrystallizer crystallizer;
    private final AdrSupersedeChecker supersedeChecker;
    private final RepoWorkspaceResolver workspaceResolver;
    private final PlatformTransactionManager txManager;
    private final ObjectMapper objectMapper;

    /** Reusable transaction template — initialized once in {@link #init()}. */
    private TransactionTemplate txTemplate;

    @PostConstruct
    void init() {
        txTemplate = new TransactionTemplate(txManager);
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    @Override
    public AdrVO generateFromRequirement(Long userId, Long repoId, Long requirementId) {
        // Load and validate requirement — must belong to both repo AND requesting user
        RequirementEntity requirement = requirementMapper.selectById(requirementId);
        if (requirement == null
                || !repoId.equals(requirement.getRepoId())
                || !userId.equals(requirement.getUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "Requirement not found");
        }

        String approach = requirement.getApproach();
        Long agentRunId = requirement.getAgentRunId();

        // Gather plan steps (fail-safe)
        List<StepNote> steps = new ArrayList<>();
        if (agentRunId != null) {
            try {
                AgentRunPlanEntity plan = agentRunPlanMapper.selectOne(
                        Wrappers.<AgentRunPlanEntity>lambdaQuery()
                                .eq(AgentRunPlanEntity::getAgentRunId, agentRunId));
                if (plan != null && plan.getPlanJson() != null) {
                    steps = parsePlanJson(plan.getPlanJson());
                }
            } catch (Exception e) {
                log.warn("AdrService: failed to load plan for agentRunId={}, using empty steps. err={}",
                        agentRunId, e.getMessage());
            }
        }

        // Gather changed files (fail-safe, distinct, cap 50)
        List<String> changedFiles = new ArrayList<>();
        if (agentRunId != null) {
            try {
                changedFiles = fileChangeLogMapper.selectList(
                                Wrappers.<FileChangeLogEntity>lambdaQuery()
                                        .eq(FileChangeLogEntity::getAgentRunId, agentRunId)
                                        .select(FileChangeLogEntity::getFilePath))
                        .stream()
                        .map(FileChangeLogEntity::getFilePath)
                        .filter(Objects::nonNull)
                        .distinct()
                        .limit(50)
                        .collect(Collectors.toList());
            } catch (Exception e) {
                log.warn("AdrService: failed to load changedFiles for agentRunId={}, using empty. err={}",
                        agentRunId, e.getMessage());
            }
        }

        // Crystallize via LLM (AdrCrystallizer is internally fail-safe)
        AdrDraft draft = crystallizer.crystallize(new CrystallizeInput(approach, steps, changedFiles));

        // Persist PROPOSED ADR
        AdrEntity entity = new AdrEntity();
        entity.setRepoId(repoId);
        entity.setUserId(userId);
        entity.setNumber(null);
        entity.setTitle(draft.title());
        entity.setStatus("PROPOSED");
        entity.setContext(draft.context());
        entity.setDecision(draft.decision());
        entity.setConsequences(draft.consequences());
        entity.setDegraded(draft.degraded() ? 1 : 0);
        entity.setSourceType("REQUIREMENT");
        entity.setSourceId(requirementId);
        entity.setDriversJson(serializeList(draft.drivers()));
        entity.setOptionsJson(serializeList(draft.options()));
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        adrMapper.insert(entity);

        // Auto-trigger supersede check against existing ACCEPTED ADRs (fail-safe — must NOT block generation)
        try {
            autoTriggerSupersede(entity.getId(), entity.getTitle(), entity.getDecision(), repoId);
        } catch (Exception ex) {
            log.warn("AdrService: auto-supersede trigger failed (fail-safe), newAdrId={} err={}",
                    entity.getId(), ex.getMessage());
        }

        return toVO(entity);
    }

    @Override
    public List<AdrVO> list(Long userId, Long repoId) {
        List<AdrEntity> entities = adrMapper.selectList(
                Wrappers.<AdrEntity>lambdaQuery()
                        .eq(AdrEntity::getRepoId, repoId)
                        .eq(AdrEntity::getUserId, userId)
                        .orderByDesc(AdrEntity::getCreatedAt));
        return entities.stream().map(this::toVO).collect(Collectors.toList());
    }

    @Override
    public AdrVO get(Long userId, Long repoId, Long adrId) {
        AdrEntity entity = adrMapper.selectById(adrId);
        if (entity == null || !repoId.equals(entity.getRepoId()) || !userId.equals(entity.getUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "ADR not found");
        }
        return toVO(entity);
    }

    @Override
    public AdrVO accept(Long userId, Long repoId, Long adrId) {
        AdrEntity entity = adrMapper.selectById(adrId);
        if (entity == null || !repoId.equals(entity.getRepoId()) || !userId.equals(entity.getUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "ADR not found");
        }

        // Idempotent: already accepted
        if ("ACCEPTED".equals(entity.getStatus())) {
            if (entity.getFilePath() != null) {
                // File was successfully written previously — true idempotent return
                return toVO(entity);
            }
            // Previous file write failed (filePath is null in DB): retry using the already-assigned number
            tryWriteMadrFile(entity, repoId, adrId);
            return toVO(entity);
        }

        // Atomically assign number + persist ACCEPTED; filePath stays null until file write succeeds
        AdrEntity accepted = assignNumberWithRetry(entity, repoId);

        // Write MADR markdown file outside the transaction (fail-safe: log only, do NOT roll back ACCEPTED)
        tryWriteMadrFile(accepted, repoId, adrId);

        return toVO(accepted);
    }

    @Override
    public AdrVO supersede(Long userId, Long repoId, Long adrId, Long supersedingAdrId) {
        // Guard: an ADR cannot supersede itself
        if (supersedingAdrId.equals(adrId)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "cannot supersede itself");
        }
        // Validate the ADR to be superseded
        AdrEntity entity = adrMapper.selectById(adrId);
        if (entity == null || !repoId.equals(entity.getRepoId()) || !userId.equals(entity.getUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "ADR not found");
        }
        // Validate the superseding ADR
        AdrEntity supersedingEntity = adrMapper.selectById(supersedingAdrId);
        if (supersedingEntity == null
                || !repoId.equals(supersedingEntity.getRepoId())
                || !userId.equals(supersedingEntity.getUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "Superseding ADR not found");
        }
        // Idempotent: already SUPERSEDED by the same ADR
        if ("SUPERSEDED".equals(entity.getStatus()) && supersedingAdrId.equals(entity.getSupersededBy())) {
            return toVO(entity);
        }
        // Mark SUPERSEDED
        entity.setStatus("SUPERSEDED");
        entity.setSupersededBy(supersedingAdrId);
        entity.setUpdatedAt(LocalDateTime.now());
        adrMapper.updateById(entity);
        return toVO(entity);
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    /**
     * Auto-trigger supersede check: compare the new ADR against the 20 most-recent ACCEPTED ADRs
     * in the same repo. For any that the checker deems contradicted/replaced, mark it SUPERSEDED.
     *
     * <p>Each individual comparison is also wrapped in try/catch so one bad check cannot abort
     * the rest. The outer call-site wraps this method entirely in try/catch as well.
     */
    private void autoTriggerSupersede(Long newAdrId, String newTitle, String newDecision, Long repoId) {
        List<AdrEntity> acceptedAdrs = adrMapper.selectList(
                Wrappers.<AdrEntity>lambdaQuery()
                        .eq(AdrEntity::getRepoId, repoId)
                        .eq(AdrEntity::getStatus, "ACCEPTED")
                        .orderByDesc(AdrEntity::getCreatedAt)
                        .last("LIMIT 20"));
        for (AdrEntity old : acceptedAdrs) {
            try {
                AdrSupersedeChecker.Verdict verdict = supersedeChecker.check(
                        newTitle, newDecision, old.getTitle(), old.getDecision());
                if (verdict.supersedes()) {
                    log.info("AdrService: auto-supersede adrId={} by newAdrId={} rationale={}",
                            old.getId(), newAdrId, verdict.rationale());
                    old.setStatus("SUPERSEDED");
                    old.setSupersededBy(newAdrId);
                    old.setUpdatedAt(LocalDateTime.now());
                    adrMapper.updateById(old);
                }
            } catch (Exception ex) {
                log.warn("AdrService: auto-supersede check failed for adrId={} err={}",
                        old.getId(), ex.getMessage());
            }
        }
    }

    /**
     * Atomically assigns the next per-repo ADR number and marks the entity ACCEPTED.
     * filePath is intentionally left null; it is set by {@link #tryWriteMadrFile} only after
     * the actual file has been written successfully.
     *
     * <p>On {@link DuplicateKeyException} (concurrent accept race on the UNIQUE KEY
     * {@code uk_adr_repo_number}), re-queries max+1 and retries up to 3 times.
     * Each retry runs in its own fresh transaction so the rolled-back inner transaction
     * does not poison the outer context.
     */
    private AdrEntity assignNumberWithRetry(AdrEntity entity, Long repoId) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return txTemplate.execute(status -> {
                    AdrEntity maxAdr = adrMapper.selectOne(
                            Wrappers.<AdrEntity>lambdaQuery()
                                    .eq(AdrEntity::getRepoId, repoId)
                                    .isNotNull(AdrEntity::getNumber)
                                    .orderByDesc(AdrEntity::getNumber)
                                    .last("LIMIT 1"));
                    int nextNumber = (maxAdr == null || maxAdr.getNumber() == null) ? 1 : maxAdr.getNumber() + 1;
                    entity.setNumber(nextNumber);
                    entity.setStatus("ACCEPTED");
                    entity.setFilePath(null); // set only after successful file write
                    entity.setUpdatedAt(LocalDateTime.now());
                    adrMapper.updateById(entity);
                    return entity;
                });
            } catch (DuplicateKeyException ex) {
                log.warn("AdrService: number allocation conflict attempt={} repoId={}, retrying",
                        attempt + 1, repoId);
            }
        }
        throw new BizException(ErrorCode.SYSTEM_ERROR, "ADR number allocation conflict");
    }

    /**
     * Try to write the MADR file for an already-ACCEPTED ADR (number must be set).
     * On success: persists {@code filePath} in DB and updates the entity in-memory so the
     * caller's {@link #toVO} returns the correct path.
     * On failure: logs WARN and leaves {@code filePath} null — the client can trigger a
     * retry by calling {@code accept} again.
     */
    private void tryWriteMadrFile(AdrEntity entity, Long repoId, Long adrId) {
        String relFilePath = String.format("docs/adr/%04d.md", entity.getNumber());
        try {
            String madrContent = renderMadr(entity);
            RepoEntity repo = repoMapper.selectById(repoId);
            if (repo != null) {
                Path repoDir = workspaceResolver.resolveRepoDirectory(repo);
                Path filePath = workspaceResolver.resolveSafeNewFilePath(repoDir, relFilePath);
                Files.createDirectories(filePath.getParent());
                Files.writeString(filePath, madrContent, StandardCharsets.UTF_8);
                log.info("AdrService: wrote ADR file adrId={} path={}", adrId, filePath);
                // Persist filePath only after the actual file has been written
                entity.setFilePath(relFilePath);
                adrMapper.updateById(entity);
            } else {
                log.warn("AdrService: repo not found repoId={}, skipping file write for adrId={}", repoId, adrId);
            }
        } catch (BizException be) {
            // Repo dir not found / path escape: log but keep ACCEPTED (fail-safe)
            log.warn("AdrService: file write skipped (BizException) adrId={} relFilePath={} err={}",
                    adrId, relFilePath, be.getMessage());
        } catch (Exception e) {
            log.warn("AdrService: file write failed adrId={} relFilePath={} err={}",
                    adrId, relFilePath, e.getMessage());
        }
    }

    /**
     * Parse plan_json: {"approach":..., "steps":[{"stepId","title","why","declaredFiles","declaredOp","insight"}]}.
     * On any parse failure, returns empty list (fail-safe).
     */
    @SuppressWarnings("unchecked")
    private List<StepNote> parsePlanJson(String planJson) {
        try {
            Map<String, Object> root = objectMapper.readValue(planJson,
                    new TypeReference<Map<String, Object>>() {});
            Object stepsObj = root.get("steps");
            if (!(stepsObj instanceof List)) {
                return new ArrayList<>();
            }
            List<StepNote> notes = new ArrayList<>();
            for (Object rawStep : (List<?>) stepsObj) {
                if (!(rawStep instanceof Map)) continue;
                Map<String, Object> stepMap = (Map<String, Object>) rawStep;
                String title = (String) stepMap.get("title");
                String why = (String) stepMap.get("why");
                String insight = (String) stepMap.get("insight");
                notes.add(new StepNote(title, why, insight));
            }
            return notes;
        } catch (Exception e) {
            log.warn("AdrService: planJson parse failed, using empty steps. err={}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Render MADR markdown. Sections (Drivers, Options) are omitted when empty.
     * Newlines are consistently {@code \n} (Unix-style) throughout.
     */
    private String renderMadr(AdrEntity entity) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("# %04d. %s\n\n", entity.getNumber(), entity.getTitle()));
        sb.append("- Status: accepted\n");
        String dateStr = entity.getCreatedAt() != null
                ? entity.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                : LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        sb.append("- Date: ").append(dateStr).append("\n\n");

        sb.append("## Context and Problem Statement\n\n");
        sb.append(entity.getContext() != null ? entity.getContext() : "").append("\n\n");

        List<String> drivers = parseJsonList(entity.getDriversJson());
        if (!drivers.isEmpty()) {
            sb.append("## Decision Drivers\n\n");
            for (String d : drivers) {
                sb.append("- ").append(d).append("\n");
            }
            sb.append("\n");
        }

        List<String> options = parseJsonList(entity.getOptionsJson());
        if (!options.isEmpty()) {
            sb.append("## Considered Options\n\n");
            for (String o : options) {
                sb.append("- ").append(o).append("\n");
            }
            sb.append("\n");
        }

        sb.append("## Decision Outcome\n\n");
        sb.append(entity.getDecision() != null ? entity.getDecision() : "").append("\n\n");

        sb.append("## Consequences\n\n");
        sb.append(entity.getConsequences() != null ? entity.getConsequences() : "").append("\n");

        return sb.toString();
    }

    private String serializeList(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list != null ? list : new ArrayList<>());
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> parseJsonList(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private AdrVO toVO(AdrEntity e) {
        AdrVO vo = new AdrVO();
        vo.setId(e.getId());
        vo.setRepoId(e.getRepoId());
        vo.setNumber(e.getNumber());
        vo.setTitle(e.getTitle());
        vo.setStatus(e.getStatus());
        vo.setContext(e.getContext());
        vo.setDecision(e.getDecision());
        vo.setConsequences(e.getConsequences());
        vo.setDrivers(parseJsonList(e.getDriversJson()));
        vo.setOptions(parseJsonList(e.getOptionsJson()));
        vo.setSourceType(e.getSourceType());
        vo.setSourceId(e.getSourceId());
        vo.setFilePath(e.getFilePath());
        vo.setSupersededBy(e.getSupersededBy());
        vo.setDegraded(e.getDegraded());
        vo.setCreatedAt(e.getCreatedAt());
        vo.setUpdatedAt(e.getUpdatedAt());
        return vo;
    }
}
