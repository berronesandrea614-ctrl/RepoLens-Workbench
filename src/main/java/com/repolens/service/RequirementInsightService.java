package com.repolens.service;

import com.repolens.domain.vo.RequirementInsightVO;

/**
 * 需求意图可视化聚合接口。
 *
 * <p>对外暴露单一方法 {@link #insight}，按三种降级形态组装并返回 {@link RequirementInsightVO}：
 * <ol>
 *   <li>有结构化计划 + 有代码改动 → 完整视图（步骤/偏差/风险/flow/panorama）。</li>
 *   <li>无结构化计划 + 有代码改动 → 改动概览视图（steps=[改动概览]，无 why/偏差）。</li>
 *   <li>纯问答（无代码改动） → 仅读依据视图（steps=[AI 的回答依据]，无 deviation/panorama）。</li>
 * </ol>
 *
 * <p>权限：checkRepoPermission + requirement 归属 repo 校验；
 * 不存在 → NOT_FOUND，不属于该 (user, repo) → FORBIDDEN。
 */
public interface RequirementInsightService {

    /**
     * 组装并返回指定需求的意图可视化 VO。
     *
     * @param userId        调用方用户 id（来自 X-User-Id 请求头）
     * @param repoId        仓库 id（路径参数）
     * @param requirementId 需求 id（路径参数）
     * @return 聚合后的 RequirementInsightVO
     */
    RequirementInsightVO insight(Long userId, Long repoId, Long requirementId);
}
