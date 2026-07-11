package com.repolens.controller;

import com.repolens.common.result.Result;
import com.repolens.security.AuthUserId;
import com.repolens.service.FeishuBridgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Receives PTY output chunks from the frontend and routes them upstream to Feishu.
 * POST {@code /api/repos/{repoId}/feishu/pty-output}
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/repos/{repoId}/feishu")
public class FeishuPtyController {

    private final FeishuBridgeService feishuBridgeService;

    /**
     * Receives a raw PTY output chunk and feeds it to the Feishu bridge.
     * Fail-safe: errors inside the bridge never propagate to the caller.
     */
    @PostMapping("/pty-output")
    public Result<Void> ptyOutput(
            @AuthUserId Long userId,
            @PathVariable Long repoId,
            @RequestBody PtyOutputReq req) {
        feishuBridgeService.onPtyOutput(userId, repoId, req.chunk());
        return Result.success(null);
    }

    public record PtyOutputReq(String chunk) {}
}
