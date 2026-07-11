package com.repolens.service;

import com.repolens.domain.vo.SensitiveFileVO;

import java.util.List;

/**
 * Sensitive file detection service (Feature I – 自动 ADR P1).
 *
 * <ul>
 *   <li>recompute — gather 4 signals, run SensitiveFileComputer, persist top-30 (delete-before-insert per repo)</li>
 *   <li>list — read persisted rows ordered by rank_no asc</li>
 * </ul>
 */
public interface SensitiveFileService {

    /**
     * Gather signals, score candidates, persist top-N rows, return as VO.
     * Idempotent: always deletes previous rows for the repo before inserting new ones.
     */
    List<SensitiveFileVO> recompute(Long userId, Long repoId);

    /**
     * Return persisted sensitive files for the repo, ordered by rank_no ascending.
     */
    List<SensitiveFileVO> list(Long userId, Long repoId);
}
