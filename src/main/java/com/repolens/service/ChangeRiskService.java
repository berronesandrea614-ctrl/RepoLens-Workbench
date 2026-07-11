package com.repolens.service;

import com.repolens.domain.entity.ChangeRiskFlagEntity;
import com.repolens.domain.vo.ChangeRiskVO;

import java.util.List;

/**
 * Change risk detection service (E-破坏性操作 P1).
 *
 * <p>Detects destructive patterns in file changes via {@code DestructiveOpDetector}
 * and persists findings to {@code change_risk_flag}. All async paths are fire-and-forget
 * and must never block or fail the write tool chain.
 */
public interface ChangeRiskService {

    /**
     * Fire-and-forget: submit async scan for the given change.
     * Any exception is silently swallowed — must never propagate to callers.
     */
    void triggerAsyncDetect(Long repoId, Long sessionId, Long changeId);

    /**
     * Synchronous scan without persistence — returns in-memory entities.
     * Used as apply-gate race-condition fallback.
     */
    List<ChangeRiskFlagEntity> detectSync(Long repoId, Long changeId);

    /**
     * Query DB for unacknowledged BLOCK-level findings for a change.
     */
    List<ChangeRiskFlagEntity> getUnacknowledgedBlockers(Long changeId);

    /**
     * List all risk flags for changes belonging to the given session (for frontend display).
     */
    List<ChangeRiskVO> listBySession(Long userId, Long repoId, Long sessionId);

    /**
     * Mark all flags for the given change as acknowledged.
     *
     * @throws com.repolens.common.exception.BizException NOT_FOUND if change does not belong to repo
     * @throws com.repolens.common.exception.BizException FORBIDDEN if user lacks repo permission
     */
    void acknowledge(Long userId, Long repoId, Long changeId);

    /**
     * Return true if the change_risk_flag table already has any record (scanned or not) for the given changeId.
     * Used by the apply gate to distinguish "async scan not yet landed (race)" from "scan done, all acked".
     */
    boolean hasFlags(Long changeId);
}
