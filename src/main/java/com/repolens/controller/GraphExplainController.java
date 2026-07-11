package com.repolens.controller;

import com.repolens.common.result.Result;
import com.repolens.domain.dto.GraphExplainRequest;
import com.repolens.security.AuthUserId;
import com.repolens.service.GraphExplainService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 调用图流程解说接口：把前端渲染出的调用链交给 LLM 生成中文流程解说。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/repos")
public class GraphExplainController {

    private final GraphExplainService graphExplainService;

    @PostMapping("/{repoId}/graph/explain")
    public Result<String> explain(@AuthUserId Long userId,
                                  @PathVariable("repoId") Long repoId,
                                  @RequestBody GraphExplainRequest req) {
        return Result.success(graphExplainService.explain(userId, repoId, req));
    }
}
