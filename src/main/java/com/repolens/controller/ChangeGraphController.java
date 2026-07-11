package com.repolens.controller;

import com.repolens.common.result.Result;
import com.repolens.domain.vo.ChangeGraphVO;
import com.repolens.security.AuthUserId;
import com.repolens.service.ChangeGraphService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 改动影响面（Blast Radius）HTTP 接口。
 * 返回某次 agent run 改动的文件、被改符号，及其上下游调用图（最多各 80 节点）。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/repos")
public class ChangeGraphController {

    private final ChangeGraphService changeGraphService;

    /**
     * 取某次 agent run 的改动影响面图。
     * 权限：X-User-Id 标识用户 + run 归属 repoId 校验（在 service 层完成）。
     */
    @GetMapping("/{repoId}/agent-runs/{runId}/change-graph")
    public Result<ChangeGraphVO> changeGraph(
            @AuthUserId Long userId,
            @PathVariable("repoId") Long repoId,
            @PathVariable("runId") Long runId) {
        return Result.success(changeGraphService.getChangeGraph(userId, repoId, runId));
    }
}
