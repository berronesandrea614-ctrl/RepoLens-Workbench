package com.repolens.service;

import com.repolens.domain.vo.AdrVO;

import java.util.List;

/**
 * ADR orchestration service.
 *
 * <ul>
 *   <li>generateFromRequirement — crystallize from requirement context, persist PROPOSED;
 *       auto-triggers supersede check against existing ACCEPTED ADRs (fail-safe)</li>
 *   <li>list — list by repo, ordered createdAt DESC, enforcing userId ownership</li>
 *   <li>get — fetch by id, validate repo + user</li>
 *   <li>accept — assign number, write docs/adr/NNNN.md, status ACCEPTED</li>
 *   <li>supersede — explicitly mark an ADR SUPERSEDED by another ADR (idempotent)</li>
 * </ul>
 */
public interface AdrService {

    AdrVO generateFromRequirement(Long userId, Long repoId, Long requirementId);

    List<AdrVO> list(Long userId, Long repoId);

    AdrVO get(Long userId, Long repoId, Long adrId);

    AdrVO accept(Long userId, Long repoId, Long adrId);

    /**
     * Explicitly mark {@code adrId} as SUPERSEDED by {@code supersedingAdrId}.
     *
     * <p>Validates that both ADRs belong to the given user and repo (else NOT_FOUND).
     * Idempotent: if {@code adrId} is already SUPERSEDED by the same {@code supersedingAdrId}, returns current VO.
     *
     * @return updated VO with status=SUPERSEDED and supersededBy=supersedingAdrId
     */
    AdrVO supersede(Long userId, Long repoId, Long adrId, Long supersedingAdrId);
}
