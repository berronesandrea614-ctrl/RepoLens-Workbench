package com.repolens.service;

import com.repolens.domain.dto.LlmSettingsUpdateRequest;
import com.repolens.domain.vo.EmbeddingSettingsVO;
import com.repolens.domain.vo.LlmSettingsVO;
import com.repolens.domain.vo.LlmTestResultVO;

/**
 * 运行时设置服务：读取/更新 LLM 配置（脱敏），以及一次性连接测试。
 */
public interface SettingsService {

    /** 返回当前运行时 LLM 设置（api-key 已脱敏）。 */
    LlmSettingsVO getLlm();

    /** 更新并持久化运行时 LLM 设置，返回脱敏后的最新视图。 */
    LlmSettingsVO updateLlm(LlmSettingsUpdateRequest request);

    /** 用请求里的临时配置发一次性测试调用，绝不持久化、绝不改动运行时配置。 */
    LlmTestResultVO testLlm(LlmSettingsUpdateRequest request);

    /** 返回当前 embedding provider 只读信息（是否 mock、模型、维度），供 UI 呈现事实。 */
    EmbeddingSettingsVO getEmbedding();
}
