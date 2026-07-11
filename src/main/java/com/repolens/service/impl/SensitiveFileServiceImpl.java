package com.repolens.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.CodeDependencyEntity;
import com.repolens.domain.entity.CodeFileEntity;
import com.repolens.domain.entity.CodeSymbolEntity;
import com.repolens.domain.entity.ComprehensionDebtFileEntity;
import com.repolens.domain.entity.FileChangeLogEntity;
import com.repolens.domain.entity.RepoConstraintRuleEntity;
import com.repolens.domain.entity.SensitiveFileEntity;
import com.repolens.domain.vo.SensitiveFileVO;
import com.repolens.mapper.CodeDependencyMapper;
import com.repolens.mapper.CodeFileMapper;
import com.repolens.mapper.CodeSymbolMapper;
import com.repolens.mapper.ComprehensionDebtFileMapper;
import com.repolens.mapper.FileChangeLogMapper;
import com.repolens.mapper.RepoConstraintRuleMapper;
import com.repolens.mapper.SensitiveFileMapper;
import com.repolens.security.PermissionService;
import com.repolens.service.SensitiveFileService;
import com.repolens.service.impl.support.SensitiveFileComputer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Sensitive file detection service implementation (Feature I – 自动 ADR P1).
 *
 * <p>Fail-safe iron law: any individual signal gather failure degrades that signal to 0/empty
 * and never propagates an exception out of {@link #recompute}.
 *
 * <p>fanIn join:
 * code_file (repoId → filePath/fileId) → code_symbol (fileId → className/methodName) →
 * code_dependency (targetSymbolName IN symbolNames). Aggregated in-memory per candidate file.
 *
 * <p>pathForbidden matching: replicates ConstraintChecker.matchesPathPattern logic
 * (package-private there; inlined here for cross-package use).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SensitiveFileServiceImpl implements SensitiveFileService {

    private final PermissionService permissionService;
    private final ComprehensionDebtFileMapper comprehensionDebtFileMapper;
    private final FileChangeLogMapper fileChangeLogMapper;
    private final CodeFileMapper codeFileMapper;
    private final CodeSymbolMapper codeSymbolMapper;
    private final CodeDependencyMapper codeDependencyMapper;
    private final RepoConstraintRuleMapper repoConstraintRuleMapper;
    private final SensitiveFileMapper sensitiveFileMapper;

    private final SensitiveFileComputer sensitiveFileComputer;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager txManager;

    /** Reusable transaction template — initialized once in {@link #init()}. */
    private TransactionTemplate txTemplate;

    private static final int TOP_N = 30;
    private static final int MAX_CANDIDATES = 200;

    @PostConstruct
    void init() {
        txTemplate = new TransactionTemplate(txManager);
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    @Override
    public List<SensitiveFileVO> recompute(Long userId, Long repoId) {
        checkPermission(userId, repoId);

        // ── Signal 1: aiRatio from comprehension_debt_file ────────────────────
        Map<String, Double> aiRatioMap = new HashMap<>();
        Set<String> candidatePaths = new LinkedHashSet<>();

        try {
            List<ComprehensionDebtFileEntity> debtFiles = comprehensionDebtFileMapper.selectList(
                    Wrappers.<ComprehensionDebtFileEntity>lambdaQuery()
                            .eq(ComprehensionDebtFileEntity::getRepoId, repoId));
            for (ComprehensionDebtFileEntity f : debtFiles) {
                if (f.getFilePath() != null) {
                    candidatePaths.add(f.getFilePath());
                    if (f.getS1AiRatio() != null) {
                        aiRatioMap.put(f.getFilePath(), f.getS1AiRatio());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("SensitiveFileService: aiRatio gather failed, degrading. repoId={} err={}",
                    repoId, e.getMessage());
        }

        // ── Signal 2: PATH_FORBIDDEN patterns ────────────────────────────────
        List<String> forbiddenPatterns = new ArrayList<>();
        try {
            List<RepoConstraintRuleEntity> rules = repoConstraintRuleMapper.selectList(
                    Wrappers.<RepoConstraintRuleEntity>lambdaQuery()
                            .eq(RepoConstraintRuleEntity::getRepoId, repoId)
                            .eq(RepoConstraintRuleEntity::getRuleType, "PATH_FORBIDDEN"));
            for (RepoConstraintRuleEntity r : rules) {
                if (r.getPattern() != null && !r.getPattern().isBlank()) {
                    forbiddenPatterns.add(r.getPattern());
                }
            }
        } catch (Exception e) {
            log.warn("SensitiveFileService: pathForbidden gather failed, degrading. repoId={} err={}",
                    repoId, e.getMessage());
        }

        // Load code_file once — reused by both PATH_FORBIDDEN expansion and fanIn gather.
        // Fix 3: avoids a duplicate full-table scan on code_file per recompute call.
        List<CodeFileEntity> allRepoFiles = List.of();
        try {
            allRepoFiles = codeFileMapper.selectList(
                    Wrappers.<CodeFileEntity>lambdaQuery()
                            .eq(CodeFileEntity::getRepoId, repoId));
        } catch (Exception e) {
            log.warn("SensitiveFileService: code_file load failed, degrading PATH_FORBIDDEN expansion and fanIn. repoId={} err={}",
                    repoId, e.getMessage());
        }

        // Expand candidate set: files matching PATH_FORBIDDEN rules
        if (!forbiddenPatterns.isEmpty()) {
            try {
                for (CodeFileEntity f : allRepoFiles) {
                    if (f.getFilePath() != null) {
                        for (String pattern : forbiddenPatterns) {
                            if (matchesPathForbidden(f.getFilePath(), pattern)) {
                                candidatePaths.add(f.getFilePath());
                                break;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("SensitiveFileService: PATH_FORBIDDEN candidate expansion failed, degrading. repoId={} err={}",
                        repoId, e.getMessage());
            }
        }

        // ── Signal 3: churn from file_change_log ──────────────────────────────
        Map<String, Integer> churnMap = new HashMap<>();
        try {
            List<FileChangeLogEntity> changes = fileChangeLogMapper.selectList(
                    Wrappers.<FileChangeLogEntity>lambdaQuery()
                            .eq(FileChangeLogEntity::getRepoId, repoId)
                            .in(FileChangeLogEntity::getStatus, List.of(
                                    FileChangeLogEntity.STATUS_APPLIED,
                                    FileChangeLogEntity.STATUS_REVERTED)));
            for (FileChangeLogEntity c : changes) {
                if (c.getFilePath() != null) {
                    churnMap.merge(c.getFilePath(), 1, Integer::sum);
                }
            }
            // Add high-churn files to candidate set
            candidatePaths.addAll(churnMap.keySet());
        } catch (Exception e) {
            log.warn("SensitiveFileService: churn gather failed, degrading. repoId={} err={}",
                    repoId, e.getMessage());
        }

        // Cap candidates at 200
        List<String> candidates = candidatePaths.stream()
                .limit(MAX_CANDIDATES)
                .collect(Collectors.toList());

        // ── Signal 4: fanIn via code_file → code_symbol → code_dependency ────
        Map<String, Integer> fanInMap = new HashMap<>();
        try {
            gatherFanIn(allRepoFiles, candidates, fanInMap);
        } catch (Exception e) {
            log.warn("SensitiveFileService: fanIn gather failed, degrading to 0. repoId={} err={}",
                    repoId, e.getMessage());
        }

        // ── Build Candidate list ──────────────────────────────────────────────
        final List<String> finalForbiddenPatterns = List.copyOf(forbiddenPatterns);
        List<SensitiveFileComputer.Candidate> candidateList = candidates.stream()
                .map(fp -> new SensitiveFileComputer.Candidate(
                        fp,
                        fanInMap.getOrDefault(fp, 0),
                        churnMap.getOrDefault(fp, 0),
                        aiRatioMap.getOrDefault(fp, 0.0),
                        finalForbiddenPatterns.stream().anyMatch(p -> matchesPathForbidden(fp, p))))
                .collect(Collectors.toList());

        // ── Compute ───────────────────────────────────────────────────────────
        List<SensitiveFileComputer.Scored> scored = sensitiveFileComputer.compute(candidateList, TOP_N);

        // ── Build entities (assign rankNo 1..N) ───────────────────────────────
        LocalDateTime now = LocalDateTime.now();
        List<SensitiveFileEntity> entities = new ArrayList<>(scored.size());
        for (int i = 0; i < scored.size(); i++) {
            SensitiveFileComputer.Scored s = scored.get(i);
            SensitiveFileEntity entity = new SensitiveFileEntity();
            entity.setRepoId(repoId);
            entity.setFilePath(s.filePath());
            entity.setFanIn(s.fanIn());
            entity.setChurn(s.churn());
            entity.setAiRatio(s.aiRatio());
            entity.setConstraintHit(s.constraintHit() ? 1 : 0);
            entity.setFinalScore(s.finalScore());
            entity.setSeverity(s.severity());
            entity.setReason(s.reason());
            entity.setSignalJson(serializeSignals(s.normalizedSignals()));
            entity.setRankNo(i + 1);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            entities.add(entity);
        }

        // ── Persist: delete-before-insert (transactional) ────────────────────
        txTemplate.execute(status -> {
            sensitiveFileMapper.delete(
                    Wrappers.<SensitiveFileEntity>lambdaQuery()
                            .eq(SensitiveFileEntity::getRepoId, repoId));
            for (SensitiveFileEntity entity : entities) {
                sensitiveFileMapper.insert(entity);
            }
            return null;
        });

        // Fix 1: read back from DB so returned VOs carry DB-assigned id values
        // and ordering is guaranteed to match rank_no asc (consistent with list()).
        return list(userId, repoId);
    }

    @Override
    public List<SensitiveFileVO> list(Long userId, Long repoId) {
        checkPermission(userId, repoId);
        List<SensitiveFileEntity> rows = sensitiveFileMapper.selectList(
                Wrappers.<SensitiveFileEntity>lambdaQuery()
                        .eq(SensitiveFileEntity::getRepoId, repoId)
                        .orderByAsc(SensitiveFileEntity::getRankNo));
        return rows.stream().map(this::toVO).collect(Collectors.toList());
    }

    // ── Private: permission check ─────────────────────────────────────────────

    private void checkPermission(Long userId, Long repoId) {
        if (!permissionService.checkRepoPermission(userId, repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "No repo permission");
        }
    }

    // ── Private: fanIn gather ──────────────────────────────────────────────────

    /**
     * Gather fanIn signal via three-step join (all in-memory):
     * 1. codeFiles (pre-loaded by caller) → filePath/fileId map
     * 2. code_symbol (fileId IN candidateFileIds) → symbolName → fileId map
     *    (symbolName = className; also className.methodName for methods)
     * 3. code_dependency (targetSymbolName IN symbolNames) → count per fileId → filePath
     *
     * Failure at any step leaves fanInMap empty for the affected files (caller degrades to 0).
     *
     * @param codeFiles pre-loaded code_file rows for this repo (Fix 3: avoids re-query)
     */
    private void gatherFanIn(List<CodeFileEntity> codeFiles, List<String> candidatePaths, Map<String, Integer> fanInMap) {
        if (candidatePaths.isEmpty()) return;

        // Step 1: build filePath ↔ fileId maps from the pre-loaded code_file rows
        Map<String, Long> pathToFileId = new HashMap<>();
        Map<Long, String> fileIdToPath = new HashMap<>();
        for (CodeFileEntity f : codeFiles) {
            if (f.getFilePath() != null && f.getId() != null) {
                pathToFileId.put(f.getFilePath(), f.getId());
                fileIdToPath.put(f.getId(), f.getFilePath());
            }
        }

        // Resolve candidate paths → fileIds
        Set<Long> candidateFileIds = candidatePaths.stream()
                .map(pathToFileId::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (candidateFileIds.isEmpty()) return;

        // Step 2: code_symbol for candidate fileIds
        List<CodeSymbolEntity> symbols = codeSymbolMapper.selectList(
                Wrappers.<CodeSymbolEntity>lambdaQuery()
                        .in(CodeSymbolEntity::getFileId, candidateFileIds));

        // symbolName → fileId; use className and className.methodName
        // Fix 2: className collision (e.g. src/main/Foo.java and src/test/Foo.java both have
        // className=Foo) is handled by putIfAbsent — later files are silently mapped to the first
        // file encountered. This is a spec-allowed approximation: fanIn for identically-named
        // classes is merged onto whichever file was indexed first. Upgrading to FQN mapping is
        // out of scope for P1.
        Map<String, Long> symbolNameToFileId = new HashMap<>();
        for (CodeSymbolEntity sym : symbols) {
            String cls = sym.getClassName();
            if (cls == null || cls.isBlank()) continue;
            Long existing = symbolNameToFileId.putIfAbsent(cls, sym.getFileId());
            if (existing != null && !existing.equals(sym.getFileId())) {
                log.debug("SensitiveFileService: fanIn className collision '{}' — fileId {} shadowed by existing fileId {}, fanIn merged onto first file",
                        cls, sym.getFileId(), existing);
            }
            String method = sym.getMethodName();
            if (method != null && !method.isBlank()) {
                symbolNameToFileId.putIfAbsent(cls + "." + method, sym.getFileId());
            }
        }
        if (symbolNameToFileId.isEmpty()) return;

        // Step 3: code_dependency targeting these symbol names
        List<CodeDependencyEntity> deps = codeDependencyMapper.selectList(
                Wrappers.<CodeDependencyEntity>lambdaQuery()
                        .in(CodeDependencyEntity::getTargetSymbolName, symbolNameToFileId.keySet()));

        // Aggregate: fileId → count
        Map<Long, Integer> fileIdToFanIn = new HashMap<>();
        for (CodeDependencyEntity dep : deps) {
            Long fileId = symbolNameToFileId.get(dep.getTargetSymbolName());
            if (fileId != null) {
                fileIdToFanIn.merge(fileId, 1, Integer::sum);
            }
        }

        // Map back to filePath
        for (Map.Entry<Long, Integer> entry : fileIdToFanIn.entrySet()) {
            String path = fileIdToPath.get(entry.getKey());
            if (path != null) {
                fanInMap.put(path, entry.getValue());
            }
        }
    }

    // ── Private: PATH_FORBIDDEN matching ──────────────────────────────────────

    /**
     * Replicates ConstraintChecker.matchesPathPattern (package-private there).
     * Pattern can be a directory name, path prefix, or path with trailing slash.
     */
    private static boolean matchesPathForbidden(String filePath, String pattern) {
        if (filePath == null || pattern == null) return false;
        String fp = filePath.replace('\\', '/');
        String p  = pattern.replace('\\', '/');
        String pNoSlash = p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
        if (fp.contains("/" + pNoSlash + "/") || fp.endsWith("/" + pNoSlash)) return true;
        if (fp.startsWith(pNoSlash + "/") || fp.equals(pNoSlash)) return true;
        if (p.contains("/") && fp.contains(p)) return true;
        return false;
    }

    // ── Private: VO conversion ────────────────────────────────────────────────

    private SensitiveFileVO toVO(SensitiveFileEntity e) {
        SensitiveFileVO vo = new SensitiveFileVO();
        vo.setId(e.getId());
        vo.setRepoId(e.getRepoId());
        vo.setFilePath(e.getFilePath());
        vo.setFanIn(e.getFanIn());
        vo.setChurn(e.getChurn());
        vo.setAiRatio(e.getAiRatio());
        vo.setConstraintHit(e.getConstraintHit() != null && e.getConstraintHit() == 1);
        vo.setFinalScore(e.getFinalScore());
        vo.setSeverity(e.getSeverity());
        vo.setReason(e.getReason());
        vo.setSignals(parseSignals(e.getSignalJson()));
        vo.setRankNo(e.getRankNo());
        vo.setCreatedAt(e.getCreatedAt());
        vo.setUpdatedAt(e.getUpdatedAt());
        return vo;
    }

    private String serializeSignals(Map<String, Double> signals) {
        try {
            return objectMapper.writeValueAsString(signals);
        } catch (Exception e) {
            log.warn("SensitiveFileService: signalJson serialize failed, using empty. err={}", e.getMessage());
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseSignals(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}
