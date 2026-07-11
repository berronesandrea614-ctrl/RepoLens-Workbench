package com.repolens.controller;

import com.repolens.common.result.Result;
import com.repolens.domain.vo.FeishuBindingVO;
import com.repolens.security.AuthUserId;
import com.repolens.service.FeishuBridgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST API for managing Feishu bot bindings per repo.
 * Base path: {@code /api/repos/{repoId}/feishu}
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/repos/{repoId}/feishu")
public class FeishuBindingController {

    private final FeishuBridgeService feishuBridgeService;

    /** List all Feishu bindings for the given repo. */
    @GetMapping("/bindings")
    public Result<List<FeishuBindingVO>> list(
            @AuthUserId Long userId,
            @PathVariable Long repoId) {
        return Result.success(feishuBridgeService.list(userId, repoId));
    }

    /** Create a new Feishu binding and start the long connection. */
    @PostMapping("/bindings")
    public Result<FeishuBindingVO> create(
            @AuthUserId Long userId,
            @PathVariable Long repoId,
            @RequestBody CreateBindingReq req) {
        return Result.success(
                feishuBridgeService.create(userId, repoId, req.botName(), req.appId(), req.appSecret()));
    }

    /** Delete a Feishu binding and disconnect. */
    @DeleteMapping("/bindings/{id}")
    public Result<Void> delete(
            @AuthUserId Long userId,
            @PathVariable Long repoId,
            @PathVariable Long id) {
        feishuBridgeService.delete(userId, repoId, id);
        return Result.success(null);
    }

    /** Test whether an App ID / Secret pair can connect successfully. */
    @PostMapping("/test-connection")
    public Result<Boolean> testConnection(
            @AuthUserId Long userId,
            @PathVariable Long repoId,
            @RequestBody TestConnectionReq req) {
        boolean ok = feishuBridgeService.testConnection(userId, repoId, req.appId(), req.appSecret());
        return Result.success(ok);
    }

    // ── Inner request DTOs ────────────────────────────────────────────────────

    public record CreateBindingReq(String botName, String appId, String appSecret) {}

    public record TestConnectionReq(String appId, String appSecret) {}
}
