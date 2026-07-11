package com.repolens.service.support;

import com.repolens.llm.config.LlmRuntimeConfig;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EditFormatPolicyTest {

    @Test
    void deepseekProvider_isStrong() {
        LlmRuntimeConfig cfg = mock(LlmRuntimeConfig.class);
        when(cfg.getProvider()).thenReturn("deepseek");
        when(cfg.getModelName()).thenReturn("deepseek-v4-pro");
        EditFormatPolicy policy = new EditFormatPolicy(cfg);
        ReflectionTestUtils.setField(policy, "editFormat", "auto");
        assertThat(policy.determineTier()).isEqualTo(EditFormatPolicy.Tier.STRONG);
    }

    @Test
    void unknownProvider_isWeak() {
        LlmRuntimeConfig cfg = mock(LlmRuntimeConfig.class);
        when(cfg.getProvider()).thenReturn("unknown");
        when(cfg.getModelName()).thenReturn("llama-7b");
        EditFormatPolicy policy = new EditFormatPolicy(cfg);
        ReflectionTestUtils.setField(policy, "editFormat", "auto");
        assertThat(policy.determineTier()).isEqualTo(EditFormatPolicy.Tier.WEAK);
    }

    @Test
    void wholeFileMode_alwaysWeak() {
        LlmRuntimeConfig cfg = mock(LlmRuntimeConfig.class);
        when(cfg.getProvider()).thenReturn("deepseek");
        when(cfg.getModelName()).thenReturn("deepseek-v4-pro");
        EditFormatPolicy policy = new EditFormatPolicy(cfg);
        ReflectionTestUtils.setField(policy, "editFormat", "whole_file");
        assertThat(policy.determineTier()).isEqualTo(EditFormatPolicy.Tier.WEAK);
    }

    @Test
    void strReplaceMode_alwaysStrong() {
        LlmRuntimeConfig cfg = mock(LlmRuntimeConfig.class);
        when(cfg.getProvider()).thenReturn("unknown");
        when(cfg.getModelName()).thenReturn("llama-7b");
        EditFormatPolicy policy = new EditFormatPolicy(cfg);
        ReflectionTestUtils.setField(policy, "editFormat", "str_replace");
        assertThat(policy.determineTier()).isEqualTo(EditFormatPolicy.Tier.STRONG);
    }

    @Test
    void gpt4_modelKeyword_isStrong() {
        LlmRuntimeConfig cfg = mock(LlmRuntimeConfig.class);
        when(cfg.getProvider()).thenReturn("unknown-provider");
        when(cfg.getModelName()).thenReturn("gpt-4-turbo");
        EditFormatPolicy policy = new EditFormatPolicy(cfg);
        ReflectionTestUtils.setField(policy, "editFormat", "auto");
        assertThat(policy.determineTier()).isEqualTo(EditFormatPolicy.Tier.STRONG);
    }
}
