package com.repolens.controller;

import com.repolens.common.result.Result;
import com.repolens.security.AuthUserId;
import com.repolens.service.impl.support.MemoryMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 系统指标读取端点。
 * 提供轻量级内存抽取异步执行的可观测性数据。
 * 无需权限校验（仅返回聚合计数器，无敏感业务数据）。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/metrics")
public class MetricsController {

    private final MemoryMetrics memoryMetrics;

    /**
     * 读取内存抽取异步执行的指标快照。
     * 返回投递、完成、失败、跳过等计数器的当前值。
     *
     * @return 包含 submitted, completed, failed, skipped 的 Map
     */
    @GetMapping("/memory")
    public Result<Map<String, Long>> getMemoryMetrics(
            @AuthUserId Long userId) {
        // X-User-Id 仅用于日志追踪，不影响指标返回（指标是全局的）
        return Result.success(memoryMetrics.snapshot());
    }
}
