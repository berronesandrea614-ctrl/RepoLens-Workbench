package com.repolens.service;

import com.repolens.domain.vo.MissionControlVO;

/**
 * Mission Control 聚合服务（H Mission Control P1）。
 * 提供指挥中心顶层视图，聚合泳道、待审队列与整体摘要。
 */
public interface MissionControlService {

    /**
     * 返回指定仓库的 Mission Control 顶层视图。
     *
     * @param userId 操作用户 ID（用于权限校验与偏差分析）
     * @param repoId 目标仓库 ID
     * @return 聚合了泳道列表、待审队列与整体摘要的视图 VO
     * @throws com.repolens.common.exception.BizException code=403 若用户无该仓库权限
     */
    MissionControlVO overview(Long userId, Long repoId);
}
