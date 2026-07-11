package com.repolens.controller;

import com.repolens.common.result.Result;
import com.repolens.service.support.SteeringQueue;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Steering 端点：运行时向正在执行的 agent run 注入用户插话。
 * POST /api/steer?runId=1&text=请检查SQL注入
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class SteeringController {

    private final SteeringQueue steeringQueue;

    @PostMapping("/steer")
    public Result<String> steer(@RequestParam Long runId, @RequestParam String text) {
        steeringQueue.push(runId, text);
        return Result.success("已注入");
    }
}
