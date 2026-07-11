package com.repolens.controller;

import com.repolens.common.result.Result;
import com.repolens.domain.dto.LlmSettingsUpdateRequest;
import com.repolens.domain.vo.EmbeddingSettingsVO;
import com.repolens.domain.vo.LlmSettingsVO;
import com.repolens.domain.vo.LlmTestResultVO;
import com.repolens.security.AuthUserId;
import com.repolens.service.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行时设置接口。
 * - GET  /api/settings/llm       读取当前 LLM 设置（api-key 脱敏）
 * - PUT  /api/settings/llm       更新并持久化 LLM 设置（空 api-key 保留原值）
 * - POST /api/settings/llm/test  用临时配置做一次性连接测试（不落库、不改运行时配置）
 * - GET  /api/settings/embedding 只读 embedding provider 信息（是否 mock、模型、维度）
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService settingsService;

    @GetMapping("/llm")
    public Result<LlmSettingsVO> getLlm(
            @AuthUserId Long userId) {
        return Result.success(settingsService.getLlm());
    }

    @PutMapping("/llm")
    public Result<LlmSettingsVO> updateLlm(
            @AuthUserId Long userId,
            @RequestBody LlmSettingsUpdateRequest request) {
        return Result.success(settingsService.updateLlm(request));
    }

    @PostMapping("/llm/test")
    public Result<LlmTestResultVO> testLlm(
            @AuthUserId Long userId,
            @RequestBody LlmSettingsUpdateRequest request) {
        return Result.success(settingsService.testLlm(request));
    }

    /**
     * 只读 embedding 信息：UI 据此提示“当前是 mock 伪向量还是真实语义向量”。
     * 不提供写接口——embedding 切换涉及 Milvus collection 维度重建，必须改环境变量并重启。
     */
    @GetMapping("/embedding")
    public Result<EmbeddingSettingsVO> getEmbedding(
            @AuthUserId Long userId) {
        return Result.success(settingsService.getEmbedding());
    }
}
