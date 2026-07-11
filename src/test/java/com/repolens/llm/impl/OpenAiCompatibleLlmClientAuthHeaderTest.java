package com.repolens.llm.impl;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleLlmClientAuthHeaderTest {

    @Test
    void blankApiKeyProducesNoAuthorizationHeader() {
        assertThat(OpenAiCompatibleLlmClient.buildAuthorizationHeader("")).isEmpty();
        assertThat(OpenAiCompatibleLlmClient.buildAuthorizationHeader("   ")).isEmpty();
        assertThat(OpenAiCompatibleLlmClient.buildAuthorizationHeader(null)).isEmpty();
    }

    @Test
    void presentApiKeyProducesBearerHeader() {
        assertThat(OpenAiCompatibleLlmClient.buildAuthorizationHeader("sk-abc"))
                .contains("Bearer sk-abc");
    }
}
