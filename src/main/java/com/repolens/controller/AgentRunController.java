package com.repolens.controller;

import com.repolens.common.result.Result;
import com.repolens.domain.vo.AgentRunTraceVO;
import com.repolens.domain.vo.AgentRunVO;
import com.repolens.security.AuthUserId;
import com.repolens.service.AgentRunService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Agent 执行记录 HTTP 接口：列出 run、取某 run 的可回放 trace。
 * 均以 X-User-Id（默认 1）标识调用者，权限校验在 service 内完成。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/repos")
public class AgentRunController {

    private final AgentRunService agentRunService;

    /** 列出该 repo（可选 sessionId 过滤）下的 agent run，最新在前。 */
    @GetMapping("/{repoId}/agent-runs")
    public Result<List<AgentRunVO>> list(
            @AuthUserId Long userId,
            @PathVariable("repoId") Long repoId,
            @RequestParam(value = "sessionId", required = false) Long sessionId) {
        return Result.success(agentRunService.list(userId, repoId, sessionId));
    }

    /** 取某 run 的完整可回放 trace（run 头 + 逐步轨迹）。 */
    @GetMapping("/{repoId}/agent-runs/{runId}/trace")
    public Result<AgentRunTraceVO> trace(
            @AuthUserId Long userId,
            @PathVariable("repoId") Long repoId,
            @PathVariable("runId") Long runId) {
        return Result.success(agentRunService.trace(userId, repoId, runId));
    }
}
