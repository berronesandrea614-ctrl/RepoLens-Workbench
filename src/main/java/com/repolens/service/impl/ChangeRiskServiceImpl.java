package com.repolens.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.ChangeRiskFlagEntity;
import com.repolens.domain.entity.FileChangeLogEntity;
import com.repolens.domain.vo.ChangeRiskVO;
import com.repolens.mapper.ChangeRiskFlagMapper;
import com.repolens.mapper.FileChangeLogMapper;
import com.repolens.security.PermissionService;
import com.repolens.service.ChangeRiskService;
import com.repolens.service.impl.support.DestructiveOpDetector;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Change risk detection service implementation (E-破坏性操作 P1).
 *
 * <p>Mirrors the fire-and-forget structure of {@code DependencyCheckServiceImpl}:
 * bounded thread pool (core=1, max=2, queue=50, DiscardPolicy) ensures writes
 * are never blocked even when the pool is saturated.
 *
 * <p>Persistence uses delete-before-insert so re-checks always produce a fresh
 * snapshot with no stale rows.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChangeRiskServiceImpl implements ChangeRiskService {

    private static final AtomicLong RISK_THREAD_COUNTER = new AtomicLong(0);

    private final ChangeRiskFlagMapper changeRiskFlagMapper;
    private final FileChangeLogMapper fileChangeLogMapper;
    private final DestructiveOpDetector detector;
    private final PermissionService permissionService;

    /** Bounded fire-and-forget thread pool — DiscardPolicy silently drops when full. */
    private final ThreadPoolExecutor asyncExecutor = new ThreadPoolExecutor(
            1, 2,
            60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(50),
            r -> {
                Thread t = new Thread(r, "risk-async-" + RISK_THREAD_COUNTER.incrementAndGet());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardPolicy()
    );

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

    // ─────────────────────────── public API ──────────────────────────────────

    @Override
    public void triggerAsyncDetect(Long repoId, Long sessionId, Long changeId) {
        asyncExecutor.submit(() -> {
            try {
                scanAndPersist(repoId, changeId);
            } catch (Exception ex) {
                log.warn("risk async failed, repoId={}, changeId={}, err={}",
                        repoId, changeId, ex.getMessage());
            }
        });
    }

    @Override
    public List<ChangeRiskFlagEntity> detectSync(Long repoId, Long changeId) {
        try {
            FileChangeLogEntity change = fileChangeLogMapper.selectById(changeId);
            if (change == null) return List.of();
            List<DestructiveOpDetector.RiskFinding> findings = detector.detect(
                    change.getOpType(), change.getFilePath(),
                    change.getOldContent(), change.getNewContent());
            return toEntities(repoId, changeId, findings);
        } catch (Exception ex) {
            log.warn("detectSync failed (non-fatal), repoId={}, changeId={}, err={}",
                    repoId, changeId, ex.getMessage());
            return List.of();
        }
    }

    @Override
    public List<ChangeRiskFlagEntity> getUnacknowledgedBlockers(Long changeId) {
        try {
            return changeRiskFlagMapper.selectList(
                    Wrappers.<ChangeRiskFlagEntity>lambdaQuery()
                            .eq(ChangeRiskFlagEntity::getChangeId, changeId)
                            .eq(ChangeRiskFlagEntity::getSeverity, "BLOCK")
                            .eq(ChangeRiskFlagEntity::getAcknowledged, 0));
        } catch (Exception ex) {
            log.warn("getUnacknowledgedBlockers failed (non-fatal), changeId={}, err={}",
                    changeId, ex.getMessage());
            return List.of();
        }
    }

    @Override
    public List<ChangeRiskVO> listBySession(Long userId, Long repoId, Long sessionId) {
        if (!permissionService.checkRepoPermission(userId, repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "No repo permission");
        }
        try {
            List<FileChangeLogEntity> changes = fileChangeLogMapper.selectList(
                    Wrappers.<FileChangeLogEntity>lambdaQuery()
                            .eq(FileChangeLogEntity::getRepoId, repoId)
                            .eq(FileChangeLogEntity::getSessionId, sessionId));
            if (changes.isEmpty()) return List.of();
            List<Long> changeIds = changes.stream().map(FileChangeLogEntity::getId).toList();
            List<ChangeRiskFlagEntity> flags = changeRiskFlagMapper.selectList(
                    Wrappers.<ChangeRiskFlagEntity>lambdaQuery()
                            .in(ChangeRiskFlagEntity::getChangeId, changeIds));
            return flags.stream().map(this::toVO).toList();
        } catch (Exception ex) {
            log.warn("listBySession failed (non-fatal), repoId={}, sessionId={}, err={}",
                    repoId, sessionId, ex.getMessage());
            return List.of();
        }
    }

    @Override
    public void acknowledge(Long userId, Long repoId, Long changeId) {
        if (!permissionService.checkRepoPermission(userId, repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "No repo permission");
        }
        FileChangeLogEntity change = fileChangeLogMapper.selectById(changeId);
        if (change == null || !repoId.equals(change.getRepoId())) {
            throw new BizException(ErrorCode.NOT_FOUND,
                    "change not found or does not belong to repo: changeId=" + changeId);
        }
        changeRiskFlagMapper.acknowledgeByChangeId(userId, changeId, LocalDateTime.now());
    }

    @Override
    public boolean hasFlags(Long changeId) {
        try {
            Long count = changeRiskFlagMapper.selectCount(
                    Wrappers.<ChangeRiskFlagEntity>lambdaQuery()
                            .eq(ChangeRiskFlagEntity::getChangeId, changeId));
            return count != null && count > 0;
        } catch (Exception ex) {
            log.warn("hasFlags failed (non-fatal), changeId={}, err={}", changeId, ex.getMessage());
            return false;
        }
    }

    // ─────────────────────────── internal ────────────────────────────────────

    /**
     * Scan a single change and persist findings using delete-before-insert.
     * Called from the async executor; also directly callable from tests.
     */
    void scanAndPersist(Long repoId, Long changeId) {
        FileChangeLogEntity change = fileChangeLogMapper.selectById(changeId);
        if (change == null) {
            log.debug("risk: changeId={} not found, skip", changeId);
            return;
        }
        List<DestructiveOpDetector.RiskFinding> findings = detector.detect(
                change.getOpType(), change.getFilePath(),
                change.getOldContent(), change.getNewContent());

        // Delete-before-insert: replace any previous scan results for this change.
        changeRiskFlagMapper.deleteByChangeId(changeId);

        for (DestructiveOpDetector.RiskFinding f : findings) {
            ChangeRiskFlagEntity entity = new ChangeRiskFlagEntity();
            entity.setChangeId(changeId);
            entity.setRepoId(repoId);
            entity.setCategory(f.category());
            entity.setRuleCode(f.ruleCode());
            entity.setSeverity(f.severity());
            entity.setReversibility(f.reversibility());
            entity.setEvidence(f.evidence());
            entity.setAcknowledged(0);
            entity.setCreatedAt(LocalDateTime.now());
            changeRiskFlagMapper.insert(entity);
        }
    }

    private List<ChangeRiskFlagEntity> toEntities(Long repoId, Long changeId,
                                                   List<DestructiveOpDetector.RiskFinding> findings) {
        List<ChangeRiskFlagEntity> result = new ArrayList<>();
        for (DestructiveOpDetector.RiskFinding f : findings) {
            ChangeRiskFlagEntity entity = new ChangeRiskFlagEntity();
            entity.setChangeId(changeId);
            entity.setRepoId(repoId);
            entity.setCategory(f.category());
            entity.setRuleCode(f.ruleCode());
            entity.setSeverity(f.severity());
            entity.setReversibility(f.reversibility());
            entity.setEvidence(f.evidence());
            entity.setAcknowledged(0);
            result.add(entity);
        }
        return result;
    }

    private ChangeRiskVO toVO(ChangeRiskFlagEntity e) {
        ChangeRiskVO vo = new ChangeRiskVO();
        vo.setChangeId(e.getChangeId());
        vo.setCategory(e.getCategory());
        vo.setRuleCode(e.getRuleCode());
        vo.setSeverity(e.getSeverity());
        vo.setReversibility(e.getReversibility());
        vo.setEvidence(e.getEvidence());
        vo.setAcknowledged(Integer.valueOf(1).equals(e.getAcknowledged()));
        return vo;
    }
}
