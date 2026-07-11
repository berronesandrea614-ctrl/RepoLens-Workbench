package com.repolens.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.config.DependencyCheckProperties;
import com.repolens.domain.entity.DependencyCheckEntity;
import com.repolens.domain.entity.DependencyRegistryCacheEntity;
import com.repolens.domain.entity.FileChangeLogEntity;
import com.repolens.domain.vo.DependencyCheckVO;
import com.repolens.mapper.DependencyCheckMapper;
import com.repolens.mapper.FileChangeLogMapper;
import com.repolens.domain.entity.EgressLogEntity;
import com.repolens.service.DependencyCheckService;
import com.repolens.service.EgressPolicy;
import com.repolens.service.impl.support.DependencyExtractor;
import com.repolens.service.impl.support.ExtractedDep;
import com.repolens.service.impl.support.JavaDependencyExtractor;
import com.repolens.service.impl.support.JsDependencyExtractor;
import com.repolens.service.impl.support.OfflineSnapshotLoader;
import com.repolens.service.impl.support.OsvClient;
import com.repolens.service.impl.support.PythonDependencyExtractor;
import com.repolens.service.impl.support.RegistryClient;
import com.repolens.service.impl.support.RegistryCacheService;
import com.repolens.service.impl.support.TyposquatDetector;
import com.repolens.service.impl.support.VerdictPriority;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dependency-check service implementation (P1).
 *
 * <p>Pipeline per changed file:
 * <ol>
 *   <li>Extract added deps via JsDependencyExtractor / PythonDependencyExtractor / JavaDependencyExtractor.</li>
 *   <li>Batch OSV query for the whole dep list (ONLINE mode only, failure-safe).</li>
 *   <li>Per-dep verdict resolution:
 *     <ul>
 *       <li>OFFLINE mode: local TyposquatDetector + OfflineSnapshotLoader only; zero network.</li>
 *       <li>ONLINE mode: check RegistryCache first, then RegistryClient + OSV; write back to cache.</li>
 *       <li>Verdict priority: MALICIOUS(1) &gt; TYPOSQUAT(2) &gt; NOT_FOUND(3) &gt; VULNERABLE(4) &gt; OK(5) &gt; UNKNOWN(6).</li>
 *     </ul>
 *   </li>
 *   <li>Persist to dependency_check; flag checkedOffline when OFFLINE mode.</li>
 * </ol>
 *
 * <p>fire-and-forget uses a bounded thread pool (DiscardPolicy). Pool-full events are silently
 * dropped; this never blocks the write tool chain.
 */
@Slf4j
@Service
public class DependencyCheckServiceImpl implements DependencyCheckService {

    private static final AtomicLong DEPCHECK_THREAD_COUNTER = new AtomicLong(0);

    private final FileChangeLogMapper fileChangeLogMapper;
    private final DependencyCheckMapper dependencyCheckMapper;
    private final JsDependencyExtractor jsExtractor;
    private final PythonDependencyExtractor pyExtractor;
    private final JavaDependencyExtractor javaExtractor;
    private final RegistryClient registryClient;
    private final TyposquatDetector typosquatDetector;
    private final OsvClient osvClient;
    private final RegistryCacheService registryCacheService;
    private final DependencyCheckProperties depcheckProperties;
    private final OfflineSnapshotLoader offlineSnapshotLoader;
    private final ObjectMapper objectMapper;

    /** 出网策略网关（可选注入）。 */
    @Autowired(required = false)
    private EgressPolicy egressPolicy;

    /** Bounded fire-and-forget thread pool, DiscardPolicy silently drops when full. */
    private final ThreadPoolExecutor asyncExecutor = new ThreadPoolExecutor(
            1, 2,
            60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(50),
            r -> {
                Thread t = new Thread(r, "depcheck-async-" + DEPCHECK_THREAD_COUNTER.incrementAndGet());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardPolicy()
    );

    public DependencyCheckServiceImpl(
            FileChangeLogMapper fileChangeLogMapper,
            DependencyCheckMapper dependencyCheckMapper,
            JsDependencyExtractor jsExtractor,
            PythonDependencyExtractor pyExtractor,
            JavaDependencyExtractor javaExtractor,
            RegistryClient registryClient,
            TyposquatDetector typosquatDetector,
            OsvClient osvClient,
            RegistryCacheService registryCacheService,
            DependencyCheckProperties depcheckProperties,
            OfflineSnapshotLoader offlineSnapshotLoader,
            ObjectMapper objectMapper) {
        this.fileChangeLogMapper = fileChangeLogMapper;
        this.dependencyCheckMapper = dependencyCheckMapper;
        this.jsExtractor = jsExtractor;
        this.pyExtractor = pyExtractor;
        this.javaExtractor = javaExtractor;
        this.registryClient = registryClient;
        this.typosquatDetector = typosquatDetector;
        this.osvClient = osvClient;
        this.registryCacheService = registryCacheService;
        this.depcheckProperties = depcheckProperties;
        this.offlineSnapshotLoader = offlineSnapshotLoader;
        this.objectMapper = objectMapper;
    }

    @PreDestroy
    void shutdown() {
        asyncExecutor.shutdown();
        try {
            if (!asyncExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                asyncExecutor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            asyncExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public List<DependencyCheckVO> checkByChangeIds(Long repoId, Long sessionId, List<Long> changeIds) {
        if (changeIds == null || changeIds.isEmpty()) return List.of();
        List<DependencyCheckVO> allResults = new ArrayList<>();
        for (Long changeId : changeIds) {
            FileChangeLogEntity change = fileChangeLogMapper.selectById(changeId);
            if (change == null || !repoId.equals(change.getRepoId())) {
                log.debug("depcheck: changeId={} not found or repo mismatch, skip", changeId);
                continue;
            }
            Long effectiveSession = sessionId != null ? sessionId : change.getSessionId();
            List<ExtractedDep> deps = extractNewDeps(change);
            List<DependencyCheckVO> results = inspectAndPersist(repoId, effectiveSession, changeId, deps);
            allResults.addAll(results);
        }
        return allResults;
    }

    @Override
    public List<DependencyCheckVO> checkBySession(Long repoId, Long sessionId) {
        if (sessionId == null) return List.of();
        List<FileChangeLogEntity> sessionChanges = fileChangeLogMapper.selectList(
                Wrappers.<FileChangeLogEntity>lambdaQuery()
                        .eq(FileChangeLogEntity::getRepoId, repoId)
                        .eq(FileChangeLogEntity::getSessionId, sessionId)
        );
        List<Long> changeIds = sessionChanges.stream().map(FileChangeLogEntity::getId).toList();
        return checkByChangeIds(repoId, sessionId, changeIds);
    }

    @Override
    public List<DependencyCheckVO> queryBySession(Long repoId, Long sessionId) {
        if (sessionId == null) return List.of();
        List<DependencyCheckEntity> entities = dependencyCheckMapper.selectList(
                Wrappers.<DependencyCheckEntity>lambdaQuery()
                        .eq(DependencyCheckEntity::getRepoId, repoId)
                        .eq(DependencyCheckEntity::getSessionId, sessionId)
                        .orderByAsc(DependencyCheckEntity::getId)
        );
        return entities.stream().map(this::toVO).toList();
    }

    @Override
    public void triggerAsyncCheck(Long repoId, Long sessionId, Long changeId) {
        asyncExecutor.submit(() -> {
            try {
                checkByChangeIds(repoId, sessionId, List.of(changeId));
            } catch (Exception ex) {
                log.warn("depcheck async failed, repoId={}, changeId={}, err={}",
                        repoId, changeId, ex.getMessage());
            }
        });
    }

    // ──────────────────────────── extraction ─────────────────────────────────

    private List<ExtractedDep> extractNewDeps(FileChangeLogEntity change) {
        String filePath = change.getFilePath();
        String old = change.getOldContent() == null ? "" : change.getOldContent();
        String neu = change.getNewContent() == null ? "" : change.getNewContent();

        List<DependencyExtractor> extractors = List.of(jsExtractor, pyExtractor, javaExtractor);
        for (DependencyExtractor extractor : extractors) {
            if (extractor.supports(filePath)) {
                try {
                    return extractor.extractAdded(filePath, old, neu);
                } catch (Exception ex) {
                    log.warn("depcheck extractor failed for {}: {}", filePath, ex.getMessage());
                    return List.of();
                }
            }
        }
        return List.of();
    }

    // ──────────────────────────── inspection ─────────────────────────────────

    private List<DependencyCheckVO> inspectAndPersist(
            Long repoId, Long sessionId, Long changeId, List<ExtractedDep> deps) {

        // Delete-before-insert: previous rows for this changeId are replaced on re-check.
        dependencyCheckMapper.deleteByChangeId(changeId);
        if (deps.isEmpty()) return List.of();

        boolean offline = depcheckProperties.getMode() == DependencyCheckProperties.Mode.OFFLINE;

        // 出网策略检查：LOCAL_ONLY 模式下跳过所有网络查询（graceful skip，不抛异常）。
        // registry/OSV 的具体目标主机在各 client 内部，这里用占位符 "registry/osv" 记录意图。
        if (!offline && egressPolicy != null) {
            try {
                egressPolicy.checkAndLog("registry.dep-check", 443, EgressLogEntity.PURPOSE_DEP_CHECK, null);
            } catch (com.repolens.common.exception.BizException blocked) {
                log.debug("depcheck: egress blocked by {} mode, skipping network checks for changeId={}",
                        egressPolicy.getMode(), changeId);
                offline = true; // 降级为纯本地模式
            } catch (Exception unexpected) {
                log.warn("depcheck: egressPolicy check failed (fail-safe), err={}", unexpected.getMessage());
            }
        }

        // Batch OSV query for all deps (ONLINE only, failure-safe - returns empty map on error).
        Map<String, String> osvVerdicts = offline ? Map.of() : osvClient.queryBatch(deps);

        List<DependencyCheckVO> results = new ArrayList<>();
        for (ExtractedDep dep : deps) {
            String osvKey = dep.ecosystem() + ":" + dep.name();
            String osvVerdict = osvVerdicts.get(osvKey); // null = no OSV record
            DependencyCheckEntity entity = buildAndPersist(
                    repoId, sessionId, changeId, dep, osvVerdict, offline);
            results.add(toVO(entity));
        }
        return results;
    }

    /**
     * Resolves verdict for a single dep and persists to DB.
     *
     * <p>Verdict resolution order:
     * <ol>
     *   <li>Offline malicious snapshot (always, regardless of mode).</li>
     *   <li>Local typosquat check (ONLINE and OFFLINE).</li>
     *   <li>If OFFLINE: stop here. Return best verdict so far or UNKNOWN.</li>
     *   <li>If ONLINE: check RegistryCache; on miss, call RegistryClient + merge OSV verdict.
     *       Write result back to cache.</li>
     * </ol>
     */
    private DependencyCheckEntity buildAndPersist(
            Long repoId, Long sessionId, Long changeId,
            ExtractedDep dep, String osvVerdict, boolean offline) {

        String verdict = null;
        String detailJson = null;

        // Step 1: offline malicious snapshot (runs in both modes)
        if (offlineSnapshotLoader.isMaliciousOffline(dep.ecosystem(), dep.name())) {
            verdict = VerdictPriority.merge(verdict, DependencyCheckEntity.VERDICT_MALICIOUS);
        }

        // Step 2: local typosquat check (both modes)
        Optional<String> typoSuggestion = checkTyposquat(dep);
        if (typoSuggestion.isPresent()) {
            verdict = VerdictPriority.merge(verdict, DependencyCheckEntity.VERDICT_TYPOSQUAT);
            if (DependencyCheckEntity.VERDICT_TYPOSQUAT.equals(verdict)) {
                int dist = TyposquatDetector.damerauLevenshtein(
                        TyposquatDetector.normalize(dep.name()),
                        TyposquatDetector.normalize(typoSuggestion.get())
                );
                detailJson = buildDetailJson("suggestion", typoSuggestion.get(), "distance", dist);
            }
        }

        // Merge OSV verdict (already obtained from batch call before this per-dep loop)
        if (osvVerdict != null) {
            verdict = VerdictPriority.merge(verdict, osvVerdict);
        }

        if (offline) {
            // OFFLINE: no network calls; return best local verdict or UNKNOWN
            String finalVerdict = verdict != null ? verdict : DependencyCheckEntity.VERDICT_UNKNOWN;
            return persist(repoId, sessionId, changeId, dep, finalVerdict, detailJson, true);
        }

        // Optimization: if verdict is already MALICIOUS(1) or TYPOSQUAT(2), registry cannot improve it.
        // NOT_FOUND(3) / VULNERABLE(4) / OK(5) / UNKNOWN(6) / null all need a registry check.
        boolean alreadyDecisive = verdict != null && VerdictPriority.priorityOf(verdict) <= 2;

        if (!alreadyDecisive) {
            // Step 3: ONLINE — check registry cache, then live registry
            Optional<DependencyRegistryCacheEntity> cached =
                    registryCacheService.get(dep.ecosystem(), dep.name());

            String registryVerdict;
            if (cached.isPresent()) {
                DependencyRegistryCacheEntity c = cached.get();
                Boolean existsFlag = c.getExistsFlag();
                registryVerdict = existsFlag == null
                        ? DependencyCheckEntity.VERDICT_UNKNOWN
                        : (existsFlag ? DependencyCheckEntity.VERDICT_OK : DependencyCheckEntity.VERDICT_NOT_FOUND);
                // Reconstruct OSV verdict from cache if live batch had no result
                if (osvVerdict == null) {
                    if (c.getMaliciousIds() != null && !c.getMaliciousIds().isBlank()) {
                        registryVerdict = VerdictPriority.merge(registryVerdict,
                                DependencyCheckEntity.VERDICT_MALICIOUS);
                    } else if (c.getVulnerableIds() != null && !c.getVulnerableIds().isBlank()) {
                        registryVerdict = VerdictPriority.merge(registryVerdict,
                                DependencyCheckEntity.VERDICT_VULNERABLE);
                    }
                }
            } else {
                // Cache miss: call live registry
                registryVerdict = checkRegistry(dep);
                // Write back to cache (failure-safe)
                Boolean existsFlag = DependencyCheckEntity.VERDICT_UNKNOWN.equals(registryVerdict)
                        ? null : DependencyCheckEntity.VERDICT_OK.equals(registryVerdict);
                String cachedMalIds =
                        DependencyCheckEntity.VERDICT_MALICIOUS.equals(osvVerdict) ? "OSV" : null;
                String cachedVulnIds =
                        DependencyCheckEntity.VERDICT_VULNERABLE.equals(osvVerdict) ? "OSV" : null;
                registryCacheService.put(dep.ecosystem(), dep.name(), existsFlag, cachedMalIds,
                        cachedVulnIds);
            }

            verdict = VerdictPriority.merge(verdict, registryVerdict);
        }

        if (verdict == null) {
            verdict = DependencyCheckEntity.VERDICT_UNKNOWN;
        }

        // Build detail JSON for notable verdicts
        if (DependencyCheckEntity.VERDICT_NOT_FOUND.equals(verdict) && detailJson == null) {
            detailJson = buildDetailJson("ecosystem", dep.ecosystem(), "packageName", dep.name());
        }

        return persist(repoId, sessionId, changeId, dep, verdict, detailJson, false);
    }

    private DependencyCheckEntity persist(
            Long repoId, Long sessionId, Long changeId,
            ExtractedDep dep, String verdict, String detailJson, boolean checkedOffline) {

        DependencyCheckEntity entity = new DependencyCheckEntity();
        entity.setRepoId(repoId);
        entity.setSessionId(sessionId);
        entity.setChangeId(changeId);
        entity.setFilePath(dep.filePath());
        entity.setEcosystem(dep.ecosystem());
        entity.setPackageName(dep.name());
        entity.setVersion(dep.version());
        entity.setSource(dep.source());
        entity.setVerdict(verdict);
        entity.setDetailJson(detailJson);
        entity.setCheckedAt(LocalDateTime.now());
        entity.setCheckedOffline(checkedOffline);
        dependencyCheckMapper.insert(entity);
        return entity;
    }

    private Optional<String> checkTyposquat(ExtractedDep dep) {
        if (ExtractedDep.ECOSYSTEM_NPM.equals(dep.ecosystem())) {
            return typosquatDetector.detectNpm(dep.name());
        } else if (ExtractedDep.ECOSYSTEM_PYPI.equals(dep.ecosystem())) {
            return typosquatDetector.detectPypi(dep.name());
        }
        return Optional.empty();
    }

    private String checkRegistry(ExtractedDep dep) {
        if (ExtractedDep.ECOSYSTEM_NPM.equals(dep.ecosystem())) {
            return registryClient.checkNpm(dep.name());
        } else if (ExtractedDep.ECOSYSTEM_PYPI.equals(dep.ecosystem())) {
            return registryClient.checkPypi(dep.name());
        } else if (ExtractedDep.ECOSYSTEM_MAVEN.equals(dep.ecosystem())) {
            // IMPORT-level deps have FQN names; MANIFEST-level have "groupId:artifactId" coords.
            if (ExtractedDep.SOURCE_IMPORT.equals(dep.source())) {
                return registryClient.checkMavenByClass(dep.name());
            } else {
                String[] parts = dep.name().split(":", 2);
                if (parts.length == 2) {
                    return registryClient.checkMaven(parts[0], parts[1]);
                }
                return DependencyCheckEntity.VERDICT_UNKNOWN;
            }
        }
        return DependencyCheckEntity.VERDICT_UNKNOWN;
    }

    // ──────────────────────────── helpers ────────────────────────────────────

    private String buildDetailJson(Object... kvPairs) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            for (int i = 0; i + 1 < kvPairs.length; i += 2) {
                map.put(String.valueOf(kvPairs[i]), kvPairs[i + 1]);
            }
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return null;
        }
    }

    private DependencyCheckVO toVO(DependencyCheckEntity e) {
        return DependencyCheckVO.builder()
                .id(e.getId())
                .repoId(e.getRepoId())
                .sessionId(e.getSessionId())
                .changeId(e.getChangeId())
                .filePath(e.getFilePath())
                .ecosystem(e.getEcosystem())
                .packageName(e.getPackageName())
                .version(e.getVersion())
                .source(e.getSource())
                .verdict(e.getVerdict())
                .detailJson(e.getDetailJson())
                .checkedAt(e.getCheckedAt())
                .checkedOffline(Boolean.TRUE.equals(e.getCheckedOffline()))
                .build();
    }
}
