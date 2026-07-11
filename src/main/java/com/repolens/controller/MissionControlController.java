package com.repolens.controller;

import com.repolens.common.result.Result;
import com.repolens.domain.vo.MissionControlVO;
import com.repolens.security.AuthUserId;
import com.repolens.service.MissionControlService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mission Control 指挥中心接口（H Mission Control P1）。
 *
 * <pre>
 * GET /api/repos/{repoId}/mission-control/overview   → 顶层聚合视图
 * </pre>
 *
 * P2/P3 端点（/lanes/{id}、/stream、/review/approve 等）待后续迭代实现。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/repos/{repoId}/mission-control")
public class MissionControlController {

    private final MissionControlService missionControlService;

    /**
     * 获取 Mission Control 顶层视图：泳道列表 + 待审队列 + 整体摘要。
     */
    @GetMapping("/overview")
    public Result<MissionControlVO> overview(
            @AuthUserId Long userId,
            @PathVariable Long repoId) {
        return Result.success(missionControlService.overview(userId, repoId));
    }
}
