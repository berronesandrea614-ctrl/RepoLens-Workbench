package com.repolens.service.support;

import com.repolens.llm.config.LlmRuntimeConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EditFormatPolicy {

    public enum Tier { STRONG, WEAK }

    private static final java.util.Set<String> STRONG_PROVIDERS =
            java.util.Set.of("deepseek", "openai", "anthropic", "gemini", "mistral");
    private static final java.util.Set<String> STRONG_MODEL_KEYWORDS =
            java.util.Set.of("gpt-4", "claude-3", "claude-4", "claude-opus", "claude-sonnet",
                    "deepseek", "qwen2.5-coder-32b", "qwen3");

    private final LlmRuntimeConfig llmConfig;

    @Value("${repolens.agent.edit-format:auto}")
    private String editFormat;

    public Tier determineTier() {
        if ("whole_file".equals(editFormat)) return Tier.WEAK;
        if ("str_replace".equals(editFormat)) return Tier.STRONG;
        String provider = normalize(llmConfig.getProvider());
        String model = normalize(llmConfig.getModelName());
        for (String sp : STRONG_PROVIDERS) {
            if (provider.contains(sp)) return Tier.STRONG;
        }
        for (String kw : STRONG_MODEL_KEYWORDS) {
            if (model.contains(kw)) return Tier.STRONG;
        }
        return Tier.WEAK;
    }

    private String normalize(String s) {
        return s == null ? "" : s.toLowerCase(java.util.Locale.ROOT);
    }
}
