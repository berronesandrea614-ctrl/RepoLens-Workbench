package com.repolens.service;

import com.repolens.domain.vo.BranchGraphVO;
import com.repolens.domain.vo.FileChangeVO;

import java.util.List;

/**
 * K方案分支图服务：并发产 N 变体 / 查分支图 / 选中一条方案。
 */
public interface SolutionBranchService {

    /**
     * Fan-out：并发跑 variantCount 个独立 agent loop，每个写自己 branchId 的 PROPOSED 变更；
     * 全部完成（或失败降级为 DISCARDED）后返回完整分支图。
     *
     * @param variantCount 变体数量，clamped 到 [2, 4]
     * @param strategies   各变体策略提示（可为 null/空，使用默认策略列表）
     */
    BranchGraphVO fanout(Long userId, Long repoId, Long sessionId, String question,
                         int variantCount, List<String> strategies);

    /**
     * 查询该 session 下全部候选方案节点，组成分支图 VO。
     */
    BranchGraphVO getBranchGraph(Long userId, Long repoId, Long sessionId);

    /**
     * 选中一条方案：
     * 1. 选中分支 status → SELECTED；
     * 2. 其余 GENERATING/READY 分支 → DISCARDED，其 PROPOSED file_change_log → REJECTED（不落盘）；
     * 3. 对选中分支调用 fileChangeService.applyAll(…, branchId, ack)。
     *
     * @param ack 用户已确认风险
     * @return apply 后的文件变更列表
     */
    List<FileChangeVO> select(Long userId, Long repoId, Long sessionId, String branchId, boolean ack);
}
