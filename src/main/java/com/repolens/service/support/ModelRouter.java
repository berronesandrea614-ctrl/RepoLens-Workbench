package com.repolens.service.support;

import com.repolens.llm.config.LlmRuntimeConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 多档模型路由：PRIMARY 跑主 loop；FAST 跑标题/摘要/分类等杂活。
 * FAST 配置缺省时回落 PRIMARY（fail-closed 不丢请求）。
 */
@Component
@RequiredArgsConstructor
public class ModelRouter {

    private final LlmRuntimeConfig llmConfig;

    @Value("${repolens.llm.fast.model-name:}")
    private String fastModelName;

    public enum Tier { PRIMARY, FAST }

    public String resolve(Tier tier) {
        if (tier == Tier.FAST && fastModelName != null && !fastModelName.isBlank()) {
            return fastModelName;
        }
        return llmConfig.getModelName();
    }
}
