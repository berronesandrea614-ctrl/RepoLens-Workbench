package com.repolens.service;

import com.repolens.domain.vo.CodeGraphVO;
import com.repolens.domain.vo.TimelineVO;

/**
 * Feature J: 架构时间轴回放服务。
 * 纯聚合 file_change_log / agent_run，不建新表。
 */
public interface ArchTimelineService {

    /**
     * 获取 repo 的时间轴（排帧列表）。
     * 每帧对应一次 agent_run，按 created_at 升序。
     *
     * @param userId 当前用户
     * @param repoId 仓库 id
     * @return 时间轴 VO（historyLimited 恒为 true）
     */
    TimelineVO getTimeline(Long userId, Long repoId);

    /**
     * 获取累积到第 frameIndex 帧的架构快照图。
     * 图节点=累积触碰符号（封顶 150，按 touchCount desc 截断）。
     * changeType 由当前帧与历史综合判断：NEW / MODIFIED / STABLE。
     *
     * @param userId     当前用户
     * @param repoId     仓库 id
     * @param frameIndex 目标帧（0-based）；越界抛 NOT_FOUND
     * @return 累积图 VO
     */
    CodeGraphVO getFrameGraph(Long userId, Long repoId, int frameIndex);
}
