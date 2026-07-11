package com.repolens.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.*;
import com.repolens.domain.enums.SymbolType;
import com.repolens.domain.vo.*;
import com.repolens.llm.LlmClient;
import com.repolens.llm.config.LlmRuntimeConfig;
import com.repolens.llm.model.LlmRequest;
import com.repolens.llm.model.LlmResponse;
import com.repolens.mapper.*;
import com.repolens.security.PermissionService;
import com.repolens.service.MilvusService;
import com.repolens.service.TraceabilityService;
import com.repolens.service.impl.support.TraceLinker;
import com.repolens.service.impl.support.TraceLinker.LinkCandidate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Feature C: 双向可追溯地图服务实现。
 *
 * <p>MVP：DECLARED（1.0） + RAG（0.5–0.8） 两路；
 * P1：CALLGRAPH（×0.7衰减）+ LLM 确认 + 脱钩检测（markDechainSafe）。
 * 全部失败安全：向量/LLM 不可用 → DECLARED-only + degraded=true。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TraceabilityServiceImpl implements TraceabilityService {

    private static final int MAX_SEEDS = 20;
    private static final int RAG_TOP_K = 10;

    private final RequirementSymbolMapper requirementSymbolMapper;
    private final TraceSnapshotMapper     traceSnapshotMapper;
    private final RequirementMapper       requirementMapper;
    private final CodeSymbolMapper        codeSymbolMapper;
    private final CodeFileMapper          codeFileMapper;
    private final CodeChunkMapper         codeChunkMapper;
    private final AgentRunMapper          agentRunMapper;
    private final AgentRunPlanMapper      agentRunPlanMapper;
    private final FileChangeLogMapper     fileChangeLogMapper;
    private final MilvusService           milvusService;
    private final PermissionService       permissionService;
    private final ObjectMapper            objectMapper;
    private final CodeDependencyMapper    codeDependencyMapper;
    private final LlmClient              llmClient;
    private final LlmRuntimeConfig       llmRuntimeConfig;

    // ── public API ────────────────────────────────────────────────────────────

    @Override
    public TraceMapVO getOrComputeMap(Long userId, Long repoId) {
        checkPermission(userId, repoId);
        TraceSnapshotEntity snap = loadSnapshot(repoId);
        if (snap != null && StringUtils.hasText(snap.getDetailJson())) {
            try {
                return objectMapper.readValue(snap.getDetailJson(), TraceMapVO.class);
            } catch (Exception ex) {
                log.warn("trace snapshot parse failed, repoId={}, recomputing", repoId);
            }
        }
        return doCompute(repoId, true);
    }

    @Override
    public TraceMapVO recompute(Long userId, Long repoId) {
        checkPermission(userId, repoId);
        return doCompute(repoId, true);
    }

    @Override
    public TraceForwardVO forwardTrace(Long userId, Long repoId, Long requirementId) {
        checkPermission(userId, repoId);
        RequirementEntity req = requirementMapper.selectById(requirementId);
        if (req == null) throw new BizException(ErrorCode.NOT_FOUND, "Requirement not found: " + requirementId);

        List<RequirementSymbolEntity> links = requirementSymbolMapper.selectList(
                Wrappers.<RequirementSymbolEntity>lambdaQuery()
                        .eq(RequirementSymbolEntity::getRequirementId, requirementId));

        List<TraceForwardVO.TraceLink> traceLinks = links.stream().map(l -> {
            String symbolName = null;
            String layer = null;
            if (l.getSymbolId() != null) {
                CodeSymbolEntity sym = codeSymbolMapper.selectById(l.getSymbolId());
                if (sym != null) {
                    symbolName = sym.getClassName() != null ? sym.getClassName() : sym.getMethodName();
                    layer = TraceLinker.inferLayer(sym.getSymbolType());
                }
            }
            return TraceForwardVO.TraceLink.builder()
                    .symbolId(l.getSymbolId())
                    .filePath(l.getFilePath())
                    .startLine(l.getStartLine())
                    .linkType(l.getLinkType())
                    .confidence(l.getConfidence() != null ? l.getConfidence() : 0.0)
                    .status(l.getStatus())
                    .symbolName(symbolName)
                    .layer(layer)
                    .build();
        }).collect(Collectors.toList());

        long linkedCount = links.stream()
                .filter(l -> RequirementSymbolEntity.STATUS_LINKED.equals(l.getStatus()))
                .count();
        double coverage = links.isEmpty() ? 0.0 : (double) linkedCount / links.size();

        return TraceForwardVO.builder()
                .requirementId(requirementId)
                .title(req.getTitle())
                .coverage(coverage)
                .links(traceLinks)
                .build();
    }

    @Override
    public TraceReverseVO reverseTrace(Long userId, Long repoId, Long symbolId) {
        checkPermission(userId, repoId);
        CodeSymbolEntity sym = codeSymbolMapper.selectById(symbolId);
        if (sym == null) throw new BizException(ErrorCode.NOT_FOUND, "Symbol not found: " + symbolId);

        List<RequirementSymbolEntity> links = requirementSymbolMapper.selectList(
                Wrappers.<RequirementSymbolEntity>lambdaQuery()
                        .eq(RequirementSymbolEntity::getSymbolId, symbolId));

        Set<Long> reqIds = links.stream().map(RequirementSymbolEntity::getRequirementId).collect(Collectors.toSet());
        Map<Long, RequirementEntity> reqMap = new HashMap<>();
        if (!reqIds.isEmpty()) {
            requirementMapper.selectList(Wrappers.<RequirementEntity>lambdaQuery()
                    .in(RequirementEntity::getId, reqIds))
                    .forEach(r -> reqMap.put(r.getId(), r));
        }

        List<TraceReverseVO.ReqLink> reqLinks = links.stream().map(l -> {
            RequirementEntity requirement = reqMap.get(l.getRequirementId());
            return TraceReverseVO.ReqLink.builder()
                    .requirementId(l.getRequirementId())
                    .title(requirement != null ? requirement.getTitle() : "(deleted)")
                    .linkType(l.getLinkType())
                    .confidence(l.getConfidence() != null ? l.getConfidence() : 0.0)
                    .status(l.getStatus())
                    .build();
        }).collect(Collectors.toList());

        String symbolName = sym.getClassName() != null ? sym.getClassName() : sym.getMethodName();

        return TraceReverseVO.builder()
                .symbolId(symbolId)
                .symbolName(symbolName)
                .layer(TraceLinker.inferLayer(sym.getSymbolType()))
                .requirements(reqLinks)
                .build();
    }

    @Override
    public List<Long> markDechainSafe(Long repoId, String filePath, boolean fileDeleted) {
        try {
            List<RequirementSymbolEntity> affected;
            if (StringUtils.hasText(filePath)) {
                affected = requirementSymbolMapper.selectList(
                        Wrappers.<RequirementSymbolEntity>lambdaQuery()
                                .eq(RequirementSymbolEntity::getFilePath, filePath));
            } else {
                return List.of();
            }
            if (affected.isEmpty()) return List.of();

            Set<Long> reqIds = new LinkedHashSet<>();
            for (RequirementSymbolEntity link : affected) {
                String newStatus = fileDeleted
                        ? RequirementSymbolEntity.STATUS_BROKEN
                        : RequirementSymbolEntity.STATUS_STALE;
                link.setStatus(newStatus);
                link.setUpdatedAt(LocalDateTime.now());
                requirementSymbolMapper.updateById(link);
                reqIds.add(link.getRequirementId());
            }
            return new ArrayList<>(reqIds);
        } catch (Exception ex) {
            log.warn("markDechainSafe failed (fail-safe), repoId={}, filePath={}, err={}",
                    repoId, filePath, ex.getMessage());
            return List.of();
        }
    }

    // ── internal computation ──────────────────────────────────────────────────

    private TraceMapVO doCompute(Long repoId, boolean save) {
        try {
            return doComputeInternal(repoId, save);
        } catch (BizException biz) {
            throw biz;
        } catch (Exception ex) {
            log.error("traceability compute failed, repoId={}, err={}", repoId, ex.getMessage(), ex);
            return TraceMapVO.builder()
                    .metrics(TraceMapVO.Metrics.builder()
                            .coverage(0).orphanCount(0).danglingCount(0).staleCount(0).build())
                    .nodes(List.of()).edges(List.of()).degraded(true).build();
        }
    }

    private TraceMapVO doComputeInternal(Long repoId, boolean save) {
        // 1. Load all requirements for this repo
        List<RequirementEntity> reqs = requirementMapper.selectList(
                Wrappers.<RequirementEntity>lambdaQuery().eq(RequirementEntity::getRepoId, repoId));

        // 2. Load all core-layer symbols for this repo
        List<CodeSymbolEntity> symbols = codeSymbolMapper.selectList(
                Wrappers.<CodeSymbolEntity>lambdaQuery().eq(CodeSymbolEntity::getRepoId, repoId));

        // 3. Compute links: DECLARED + RAG + CALLGRAPH paths
        boolean degraded = false;
        List<LinkCandidate> allCandidates = new ArrayList<>();
        for (RequirementEntity req : reqs) {
            List<LinkCandidate> declared = computeDeclaredLinks(req, repoId);
            allCandidates.addAll(declared);
            try {
                List<LinkCandidate> rag = computeRagLinks(req, repoId);
                allCandidates.addAll(rag);
            } catch (Exception ex) {
                log.warn("RAG link path failed for req={}, degraded, err={}", req.getId(), ex.getMessage());
                degraded = true;
            }
        }

        // CALLGRAPH (P1): 1-hop propagation from DECLARED/RAG seeds
        try {
            List<LinkCandidate> callgraph = computeCallgraphLinks(allCandidates, repoId);
            allCandidates.addAll(callgraph);
        } catch (Exception ex) {
            log.warn("CALLGRAPH link path failed, repoId={}, err={}", repoId, ex.getMessage());
        }

        // LLM confirmation (P1): confirm low-confidence candidates
        if (!allCandidates.isEmpty()) {
            try {
                allCandidates = confirmWithLlm(allCandidates, reqs);
            } catch (Exception ex) {
                log.warn("LLM confirmation failed (skipped), repoId={}, err={}", repoId, ex.getMessage());
            }
        }

        // 4. Merge & dedup
        List<LinkCandidate> merged = TraceLinker.mergeAndDedup(allCandidates);

        // 5. Persist to requirement_symbol
        Set<Long> reqIdSet = reqs.stream().map(RequirementEntity::getId).collect(Collectors.toSet());
        persistLinks(repoId, reqIdSet, merged);

        // 6. Reload from DB
        List<RequirementSymbolEntity> dbLinks;
        if (!reqIdSet.isEmpty()) {
            dbLinks = requirementSymbolMapper.selectList(
                    Wrappers.<RequirementSymbolEntity>lambdaQuery()
                            .in(RequirementSymbolEntity::getRequirementId, reqIdSet));
        } else {
            dbLinks = List.of();
        }

        // 7. Compute metrics
        Set<Long> allReqIds = reqIdSet;
        Set<Long> linkedReqIds = dbLinks.stream()
                .filter(l -> RequirementSymbolEntity.STATUS_LINKED.equals(l.getStatus()))
                .map(RequirementSymbolEntity::getRequirementId).collect(Collectors.toSet());
        double coverage = TraceLinker.computeCoverage(allReqIds, linkedReqIds);

        Set<Long> linkedSymbolIds = dbLinks.stream()
                .filter(l -> RequirementSymbolEntity.STATUS_LINKED.equals(l.getStatus()))
                .filter(l -> l.getSymbolId() != null)
                .map(RequirementSymbolEntity::getSymbolId).collect(Collectors.toSet());

        long orphanCount = symbols.stream()
                .filter(s -> TraceLinker.isCoreLayer(s.getSymbolType()))
                .filter(s -> !linkedSymbolIds.contains(s.getId()))
                .count();
        long danglingCount = allReqIds.stream().filter(id -> !linkedReqIds.contains(id)).count();
        long staleCount = dbLinks.stream()
                .filter(l -> !RequirementSymbolEntity.STATUS_LINKED.equals(l.getStatus()))
                .count();

        // 8. Build bipartite graph nodes + edges
        Map<Long, RequirementEntity> reqMap = reqs.stream()
                .collect(Collectors.toMap(RequirementEntity::getId, r -> r));
        Map<Long, CodeSymbolEntity> symMap = symbols.stream()
                .collect(Collectors.toMap(CodeSymbolEntity::getId, s -> s));

        List<TraceMapVO.TraceNode> nodes = new ArrayList<>();
        List<TraceMapVO.TraceEdge> edges = new ArrayList<>();
        Set<Long> addedReqs = new LinkedHashSet<>();
        Set<Long> addedSyms = new LinkedHashSet<>();

        for (RequirementSymbolEntity l : dbLinks) {
            if (l.getSymbolId() == null) continue;
            if (!addedReqs.contains(l.getRequirementId())) {
                RequirementEntity req = reqMap.get(l.getRequirementId());
                String flag = linkedReqIds.contains(l.getRequirementId()) ? null : "dangling";
                nodes.add(TraceMapVO.TraceNode.builder()
                        .nodeType("req").id("req_" + l.getRequirementId())
                        .label(req != null ? truncate(req.getTitle(), 40) : "Req#" + l.getRequirementId())
                        .flag(flag).build());
                addedReqs.add(l.getRequirementId());
            }
            if (!addedSyms.contains(l.getSymbolId())) {
                CodeSymbolEntity sym = symMap.get(l.getSymbolId());
                String layer = sym != null ? TraceLinker.inferLayer(sym.getSymbolType()) : null;
                String label = sym != null
                        ? (sym.getClassName() != null ? sym.getClassName() : sym.getMethodName())
                        : "Sym#" + l.getSymbolId();
                String symFlag = linkedSymbolIds.contains(l.getSymbolId()) ? null : "orphan";
                nodes.add(TraceMapVO.TraceNode.builder()
                        .nodeType("sym").id("sym_" + l.getSymbolId())
                        .label(truncate(label, 40)).layer(layer).flag(symFlag).build());
                addedSyms.add(l.getSymbolId());
            }
            edges.add(TraceMapVO.TraceEdge.builder()
                    .source("req_" + l.getRequirementId())
                    .target("sym_" + l.getSymbolId())
                    .linkType(l.getLinkType())
                    .confidence(l.getConfidence() != null ? l.getConfidence() : 1.0)
                    .status(RequirementSymbolEntity.STATUS_LINKED.equals(l.getStatus()) ? "linked" : "stale")
                    .build());
        }

        // Add dangling req nodes (req with no linked symbols)
        for (RequirementEntity req : reqs) {
            if (!addedReqs.contains(req.getId())) {
                nodes.add(TraceMapVO.TraceNode.builder()
                        .nodeType("req").id("req_" + req.getId())
                        .label(truncate(req.getTitle(), 40)).flag("dangling").build());
            }
        }

        TraceMapVO.Metrics metrics = TraceMapVO.Metrics.builder()
                .coverage(round2(coverage))
                .orphanCount((int) orphanCount)
                .danglingCount((int) danglingCount)
                .staleCount((int) staleCount)
                .build();

        TraceMapVO vo = TraceMapVO.builder()
                .metrics(metrics).nodes(nodes).edges(edges).degraded(degraded).build();

        if (save) saveSnapshot(repoId, vo);
        return vo;
    }

    // ── DECLARED link path ────────────────────────────────────────────────────

    private List<LinkCandidate> computeDeclaredLinks(RequirementEntity req, Long repoId) {
        List<LinkCandidate> results = new ArrayList<>();
        Set<String> filePaths = new LinkedHashSet<>();

        // From agent_run_plan.declaredFiles
        if (req.getAgentRunId() != null) {
            try {
                AgentRunPlanEntity plan = agentRunPlanMapper.selectOne(
                        Wrappers.<AgentRunPlanEntity>lambdaQuery()
                                .eq(AgentRunPlanEntity::getAgentRunId, req.getAgentRunId()));
                if (plan != null && StringUtils.hasText(plan.getPlanJson())) {
                    List<?> steps = objectMapper.readValue(plan.getPlanJson(), List.class);
                    for (Object step : steps) {
                        if (step instanceof Map<?, ?> m) {
                            Object df = m.get("declaredFiles");
                            if (df instanceof List<?> list) {
                                list.forEach(f -> { if (f != null) filePaths.add(f.toString()); });
                            }
                        }
                    }
                }
            } catch (Exception ex) {
                log.warn("parse declaredFiles failed, reqId={}, err={}", req.getId(), ex.getMessage());
            }
        }

        // From file_change_log for this session
        if (req.getSessionId() != null) {
            try {
                fileChangeLogMapper.selectList(
                        Wrappers.<FileChangeLogEntity>lambdaQuery()
                                .eq(FileChangeLogEntity::getSessionId, req.getSessionId())
                                .eq(FileChangeLogEntity::getRepoId, repoId))
                        .forEach(c -> { if (c.getFilePath() != null) filePaths.add(c.getFilePath()); });
            } catch (Exception ex) {
                log.warn("load file_change_log failed, reqId={}, err={}", req.getId(), ex.getMessage());
            }
        }

        // Cap file paths to MAX_SEEDS
        List<String> seedPaths = new ArrayList<>(filePaths);
        if (seedPaths.size() > MAX_SEEDS) seedPaths = seedPaths.subList(0, MAX_SEEDS);

        // Resolve file path → code_symbol
        for (String fp : seedPaths) {
            try {
                CodeFileEntity file = codeFileMapper.selectOne(
                        Wrappers.<CodeFileEntity>lambdaQuery()
                                .eq(CodeFileEntity::getRepoId, repoId)
                                .eq(CodeFileEntity::getFilePath, fp));
                if (file == null) {
                    // File not indexed: add file-only link
                    results.add(new LinkCandidate(req.getId(), null, fp, "DECLARED", 1.0));
                    continue;
                }
                List<CodeSymbolEntity> syms = codeSymbolMapper.selectList(
                        Wrappers.<CodeSymbolEntity>lambdaQuery()
                                .eq(CodeSymbolEntity::getFileId, file.getId()));
                if (syms.isEmpty()) {
                    results.add(new LinkCandidate(req.getId(), null, fp, "DECLARED", 1.0));
                } else {
                    for (CodeSymbolEntity sym : syms) {
                        results.add(new LinkCandidate(req.getId(), sym.getId(), fp, "DECLARED", 1.0));
                    }
                }
            } catch (Exception ex) {
                log.warn("resolve symbol for file failed, file={}, err={}", fp, ex.getMessage());
            }
        }
        return results;
    }

    // ── RAG link path ─────────────────────────────────────────────────────────

    private List<LinkCandidate> computeRagLinks(RequirementEntity req, Long repoId) {
        List<LinkCandidate> results = new ArrayList<>();
        String query = buildRagQuery(req);
        if (!StringUtils.hasText(query)) return results;

        List<VectorSearchHitVO> hits = milvusService.search(query, repoId, RAG_TOP_K);
        if (hits == null || hits.isEmpty()) return results;

        for (VectorSearchHitVO hit : hits) {
            double confidence = mapScore(hit.getScore());
            Long symbolId = null;

            if (StringUtils.hasText(hit.getChunkId())) {
                try {
                    CodeChunkEntity chunk = codeChunkMapper.selectOne(
                            Wrappers.<CodeChunkEntity>lambdaQuery()
                                    .eq(CodeChunkEntity::getChunkId, hit.getChunkId()));
                    if (chunk != null) symbolId = chunk.getSymbolId();
                } catch (Exception ex) {
                    log.warn("resolve chunk symbol failed, chunkId={}, err={}", hit.getChunkId(), ex.getMessage());
                }
            }
            results.add(new LinkCandidate(req.getId(), symbolId, hit.getFilePath(), "RAG", confidence));
        }
        return results;
    }

    private String buildRagQuery(RequirementEntity req) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(req.getTitle()))    sb.append(req.getTitle()).append(" ");
        if (StringUtils.hasText(req.getSummary()))  sb.append(req.getSummary()).append(" ");
        if (StringUtils.hasText(req.getApproach())) sb.append(req.getApproach());
        return sb.toString().trim();
    }

    /** Map Milvus COSINE score [0,1] → confidence [0.5, 0.8]. */
    private double mapScore(Float score) {
        if (score == null) return 0.5;
        double s = Math.max(0.0, Math.min(1.0, score));
        return 0.5 + s * 0.3;
    }

    // ── CALLGRAPH link path (P1) ──────────────────────────────────────────────

    private List<LinkCandidate> computeCallgraphLinks(List<LinkCandidate> seeds, Long repoId) {
        List<LinkCandidate> results = new ArrayList<>();
        // Collect unique seed symbol IDs (DECLARED + RAG only, cap at MAX_SEEDS)
        Set<Long> seedIds = seeds.stream()
                .filter(c -> c.symbolId() != null
                        && ("DECLARED".equals(c.linkType()) || "RAG".equals(c.linkType())))
                .map(LinkCandidate::symbolId)
                .limit(MAX_SEEDS)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // Build: symbolId → max confidence, symbolId → requirementId
        Map<Long, Double> symToMaxConf = new HashMap<>();
        Map<Long, Long>   symToReq     = new HashMap<>();
        for (LinkCandidate c : seeds) {
            if (c.symbolId() == null) continue;
            symToMaxConf.merge(c.symbolId(), c.confidence(), Math::max);
            symToReq.put(c.symbolId(), c.requirementId());
        }

        for (Long seedId : seedIds) {
            try {
                List<CodeDependencyEntity> deps = codeDependencyMapper.selectList(
                        Wrappers.<CodeDependencyEntity>lambdaQuery()
                                .eq(CodeDependencyEntity::getRepoId, repoId)
                                .eq(CodeDependencyEntity::getSourceSymbolId, seedId));
                for (CodeDependencyEntity dep : deps) {
                    // Resolve targetSymbolName → code_symbol
                    List<CodeSymbolEntity> targets = codeSymbolMapper.selectList(
                            Wrappers.<CodeSymbolEntity>lambdaQuery()
                                    .eq(CodeSymbolEntity::getRepoId, repoId)
                                    .likeRight(CodeSymbolEntity::getClassName, dep.getTargetSymbolName()));
                    for (CodeSymbolEntity target : targets) {
                        double parentConf = symToMaxConf.getOrDefault(seedId, 1.0);
                        double depConf = dep.getConfidence() != null
                                ? dep.getConfidence().doubleValue() : 1.0;
                        double decayed = parentConf * 0.7 * depConf;
                        Long reqId = symToReq.get(seedId);
                        if (reqId != null) {
                            results.add(new LinkCandidate(reqId, target.getId(),
                                    null, "CALLGRAPH", decayed));
                        }
                    }
                }
            } catch (Exception ex) {
                log.warn("CALLGRAPH 1-hop failed, seedId={}, err={}", seedId, ex.getMessage());
            }
        }
        return results;
    }

    // ── LLM confirmation (P1) ─────────────────────────────────────────────────

    /**
     * LLM single-shot confirmation: ask LLM to confirm or reject low-confidence candidates.
     * Failure-safe: any exception returns original candidates unchanged.
     * Candidates with confidence ≥ 0.9 are not sent to LLM (already trusted).
     */
    private List<LinkCandidate> confirmWithLlm(List<LinkCandidate> candidates,
                                                List<RequirementEntity> reqs) {
        List<LinkCandidate> low = candidates.stream()
                .filter(c -> c.confidence() < 0.9 && c.symbolId() != null)
                .limit(10)
                .collect(Collectors.toList());
        if (low.isEmpty()) return candidates;

        Map<Long, RequirementEntity> reqMap = reqs.stream()
                .collect(Collectors.toMap(RequirementEntity::getId, r -> r));
        Map<Long, CodeSymbolEntity> symCache = new HashMap<>();

        StringBuilder prompt = new StringBuilder("判断以下需求与代码符号的关联是否合理（回答 CONFIRM 或 REJECT，每行一个）：\n");
        List<String> keys = new ArrayList<>();
        for (LinkCandidate c : low) {
            RequirementEntity req = reqMap.get(c.requirementId());
            if (req == null) continue;
            CodeSymbolEntity sym = symCache.computeIfAbsent(c.symbolId(),
                    id -> codeSymbolMapper.selectById(id));
            if (sym == null) continue;
            String key = c.requirementId() + ":" + c.symbolId();
            keys.add(key);
            String symName = sym.getClassName() != null ? sym.getClassName() : sym.getMethodName();
            prompt.append(keys.size()).append(". 需求: ").append(req.getTitle())
                    .append(" | 符号: ").append(symName).append("\n");
        }
        if (keys.isEmpty()) return candidates;

        try {
            LlmResponse resp = llmClient.generate(
                    LlmRequest.builder()
                            .modelName(llmRuntimeConfig.getModelName())
                            .systemPrompt("你是代码可追溯性审核器。")
                            .userPrompt(prompt.toString())
                            .temperature(0.0)
                            .timeoutMs(llmRuntimeConfig.getTimeoutMs())
                            .build());
            if (resp == null || !StringUtils.hasText(resp.getContent())) return candidates;

            Set<String> rejected = new HashSet<>();
            String[] lines = resp.getContent().split("\n");
            for (int i = 0; i < lines.length && i < keys.size(); i++) {
                if (lines[i].toUpperCase().contains("REJECT")) rejected.add(keys.get(i));
            }

            return candidates.stream()
                    .filter(c -> {
                        String key = c.requirementId() + ":" + c.symbolId();
                        return !rejected.contains(key);
                    }).collect(Collectors.toList());
        } catch (Exception ex) {
            log.warn("LLM confirmation call failed, skipping, err={}", ex.getMessage());
            return candidates;
        }
    }

    // ── DB helpers ────────────────────────────────────────────────────────────

    private void persistLinks(Long repoId, Set<Long> reqIdSet, List<LinkCandidate> merged) {
        try {
            if (!reqIdSet.isEmpty()) {
                // Delete old auto-computed links (DECLARED/RAG/CALLGRAPH), keep MANUAL
                requirementSymbolMapper.delete(
                        Wrappers.<RequirementSymbolEntity>lambdaQuery()
                                .in(RequirementSymbolEntity::getRequirementId, reqIdSet)
                                .in(RequirementSymbolEntity::getLinkType,
                                        List.of("DECLARED", "RAG", "CALLGRAPH")));
            }
            for (LinkCandidate c : merged) {
                RequirementSymbolEntity entity = new RequirementSymbolEntity();
                entity.setRequirementId(c.requirementId());
                entity.setSymbolId(c.symbolId());
                entity.setFilePath(c.filePath());
                entity.setLinkType(c.linkType());
                entity.setConfidence(c.confidence());
                entity.setStatus(RequirementSymbolEntity.STATUS_LINKED);
                entity.setUpdatedAt(LocalDateTime.now());
                requirementSymbolMapper.insert(entity);
            }
        } catch (Exception ex) {
            log.warn("persistLinks failed, repoId={}, err={}", repoId, ex.getMessage());
        }
    }

    private TraceSnapshotEntity loadSnapshot(Long repoId) {
        try {
            return traceSnapshotMapper.selectOne(
                    Wrappers.<TraceSnapshotEntity>lambdaQuery()
                            .eq(TraceSnapshotEntity::getRepoId, repoId));
        } catch (Exception ex) {
            log.warn("load trace snapshot failed, repoId={}, err={}", repoId, ex.getMessage());
            return null;
        }
    }

    private void saveSnapshot(Long repoId, TraceMapVO vo) {
        try {
            String json = objectMapper.writeValueAsString(vo);
            TraceSnapshotEntity existing = loadSnapshot(repoId);
            if (existing != null) {
                existing.setCoverage(vo.getMetrics().getCoverage());
                existing.setOrphanCount(vo.getMetrics().getOrphanCount());
                existing.setDanglingCount(vo.getMetrics().getDanglingCount());
                existing.setStaleCount(vo.getMetrics().getStaleCount());
                existing.setDetailJson(json);
                existing.setDegraded(vo.isDegraded() ? 1 : 0);
                existing.setComputedAt(LocalDateTime.now());
                traceSnapshotMapper.updateById(existing);
            } else {
                TraceSnapshotEntity snap = new TraceSnapshotEntity();
                snap.setRepoId(repoId);
                snap.setCoverage(vo.getMetrics().getCoverage());
                snap.setOrphanCount(vo.getMetrics().getOrphanCount());
                snap.setDanglingCount(vo.getMetrics().getDanglingCount());
                snap.setStaleCount(vo.getMetrics().getStaleCount());
                snap.setDetailJson(json);
                snap.setDegraded(vo.isDegraded() ? 1 : 0);
                snap.setComputedAt(LocalDateTime.now());
                traceSnapshotMapper.insert(snap);
            }
        } catch (Exception ex) {
            log.warn("save trace snapshot failed, repoId={}, err={}", repoId, ex.getMessage());
        }
    }

    private void checkPermission(Long userId, Long repoId) {
        if (!permissionService.checkRepoPermission(userId, repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "No permission for repo " + repoId);
        }
    }

    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "…" : s;
    }
}
