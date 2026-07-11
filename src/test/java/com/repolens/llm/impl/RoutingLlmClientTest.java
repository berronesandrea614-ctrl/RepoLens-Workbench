package com.repolens.llm.impl;

import com.repolens.llm.LlmClientException;
import com.repolens.llm.config.LlmRuntimeConfig;
import com.repolens.llm.model.LlmRequest;
import com.repolens.llm.model.LlmResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoutingLlmClientTest {

    @Mock
    private MockLlmClient mockLlmClient;
    @Mock
    private OpenAiCompatibleLlmClient openAiCompatibleLlmClient;

    private RoutingLlmClient newRouter(String provider, MockLlmClient mockClient, OpenAiCompatibleLlmClient openai) {
        return new RoutingLlmClient(mockClient, openai, runtimeConfig(provider));
    }

    /** 构造一个仅设置 provider 的运行时配置（不触发 DB init）。 */
    private static LlmRuntimeConfig runtimeConfig(String provider) {
        LlmRuntimeConfig cfg = new LlmRuntimeConfig(null);
        ReflectionTestUtils.setField(cfg, "provider", provider);
        return cfg;
    }

    // ── existing tests ──────────────────────────────────────────────────────

    @Test
    void generate_shouldRouteDeepseekCompatibleToOpenAiClient() {
        RoutingLlmClient client = new RoutingLlmClient(mockLlmClient, openAiCompatibleLlmClient,
                runtimeConfig("DeepSeek-Compatible"));

        when(openAiCompatibleLlmClient.generate(any())).thenReturn(LlmResponse.builder().content("ok").build());

        LlmResponse response = client.generate(LlmRequest.builder().userPrompt("q").build());

        Assertions.assertEquals("ok", response.getContent());
        verify(openAiCompatibleLlmClient).generate(any());
    }

    @Test
    void generate_shouldThrowWhenProviderUnsupported() {
        RoutingLlmClient client = new RoutingLlmClient(mockLlmClient, openAiCompatibleLlmClient,
                runtimeConfig("unknown-provider"));

        LlmClientException ex = Assertions.assertThrows(
                LlmClientException.class,
                () -> client.generate(LlmRequest.builder().userPrompt("q").build())
        );

        Assertions.assertEquals("LLM_CONFIG_MISSING", ex.getErrorCode());
        Assertions.assertTrue(ex.getMessage().contains("Unsupported"));
    }

    // ── new ollama / local routing tests (Task 1) ──────────────────────────

    @Test
    void routesOllamaToOpenAiCompatibleClient() {
        MockLlmClient mockClient = mock(MockLlmClient.class);
        OpenAiCompatibleLlmClient openai = mock(OpenAiCompatibleLlmClient.class);
        LlmResponse resp = LlmResponse.builder().success(true).build();
        when(openai.generate(any())).thenReturn(resp);

        RoutingLlmClient router = newRouter("ollama", mockClient, openai);
        router.generate(LlmRequest.builder().build());

        verify(openai).generate(any());
        verifyNoInteractions(mockClient);
    }

    @Test
    void routesLocalToOpenAiCompatibleClient() {
        MockLlmClient mockClient = mock(MockLlmClient.class);
        OpenAiCompatibleLlmClient openai = mock(OpenAiCompatibleLlmClient.class);
        when(openai.generate(any())).thenReturn(LlmResponse.builder().success(true).build());

        RoutingLlmClient router = newRouter("LOCAL", mockClient, openai);
        router.generate(LlmRequest.builder().build());

        verify(openai).generate(any());
    }

    @Test
    void routesMockToMockClient() {
        MockLlmClient mockClient = mock(MockLlmClient.class);
        OpenAiCompatibleLlmClient openai = mock(OpenAiCompatibleLlmClient.class);
        when(mockClient.generate(any())).thenReturn(LlmResponse.builder().success(true).build());

        RoutingLlmClient router = newRouter("mock", mockClient, openai);
        router.generate(LlmRequest.builder().build());

        verify(mockClient).generate(any());
        verifyNoInteractions(openai);
    }

    @Test
    void unknownProviderThrows() {
        RoutingLlmClient router = newRouter("does-not-exist",
                mock(MockLlmClient.class), mock(OpenAiCompatibleLlmClient.class));
        assertThatThrownBy(() -> router.generate(LlmRequest.builder().build()))
                .isInstanceOf(LlmClientException.class);
    }
}
