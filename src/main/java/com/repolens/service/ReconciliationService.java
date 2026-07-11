package com.repolens.service;

import com.repolens.domain.vo.ReconciliationVO;

/**
 * 计划 vs 实际对账服务接口（Feature B P1）。
 *
 * <p>惰性计算 + 快照：GET 时若无快照或已过时则计算并存入 requirement_reconciliation；
 * recompute 强制重算（apply/revert 后调用）。全确定性，不依赖 LLM。
 */
public interface ReconciliationService {

    /**
     * 获取某需求的对账结果（惰性：有快照直接返回，否则计算后存快照）。
     *
     * @param userId        调用者 ID（权限校验用）
     * @param repoId        仓库 ID
     * @param requirementId 需求 ID
     * @return 对账结果 VO（无论是否有计划都不抛异常，降级返回 degrade=true）
     */
    ReconciliationVO getOrCompute(Long userId, Long repoId, Long requirementId);

    /**
     * 强制重算（忽略快照缓存，计算后更新快照）。
     *
     * @param userId        调用者 ID
     * @param repoId        仓库 ID
     * @param requirementId 需求 ID
     * @return 最新对账结果 VO
     */
    ReconciliationVO recompute(Long userId, Long repoId, Long requirementId);
}
