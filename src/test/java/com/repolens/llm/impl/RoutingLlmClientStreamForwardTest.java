package com.repolens.llm.impl;

import com.repolens.llm.StreamWithToolsListener;
import com.repolens.llm.config.LlmRuntimeConfig;
import com.repolens.llm.model.LlmRequest;
import com.repolens.llm.model.LlmResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 验证 RoutingLlmClient 正确转发 generateStreamWithTools 到实际 LLM 客户端。
 *
 * 历史坑：之前漏转发 generateStream 导致假流式（接口 default 不经真实客户端）。
 * 这里专项验证 generateStreamWithTools 也走同一转发路径。
 */
@ExtendWith(MockitoExtension.class)
class RoutingLlmClientStreamForwardTest {

    @Mock
    private MockLlmClient mockLlmClient;
    @Mock
    private OpenAiCompatibleLlmClient openAiCompatibleLlmClient;

    private RoutingLlmClient router(String provider) {
        LlmRuntimeConfig cfg = new LlmRuntimeConfig(null);
        ReflectionTestUtils.setField(cfg, "provider", provider);
        return new RoutingLlmClient(mockLlmClient, openAiCompatibleLlmClient, cfg);
    }

    @Test
    void generateStreamWithTools_routesOpenAiCompatibleCorrectly() {
        RoutingLlmClient client = router("openai-compatible");
        // openAiCompatibleLlmClient.generateStreamWithTools 是真实方法，需要 doNothing 或 doAnswer
        doNothing().when(openAiCompatibleLlmClient).generateStreamWithTools(any(), any());

        client.generateStreamWithTools(LlmRequest.builder().build(), new StreamWithToolsListener() {
            @Override public void onContentToken(String token) {}
            @Override public void onToolCallStart(String toolName) {}
            @Override public void onDone(LlmResponse response) {}
        });

        // 转发到 openAiCompatibleLlmClient，不走 mockLlmClient
        verify(openAiCompatibleLlmClient).generateStreamWithTools(any(LlmRequest.class), any(StreamWithToolsListener.class));
        verifyNoInteractions(mockLlmClient);
    }

    @Test
    void generateStreamWithTools_routesDeepseekCompatibleCorrectly() {
        RoutingLlmClient client = router("deepseek-compatible");
        doNothing().when(openAiCompatibleLlmClient).generateStreamWithTools(any(), any());

        client.generateStreamWithTools(LlmRequest.builder().build(), new StreamWithToolsListener() {
            @Override public void onContentToken(String token) {}
            @Override public void onToolCallStart(String toolName) {}
            @Override public void onDone(LlmResponse response) {}
        });

        verify(openAiCompatibleLlmClient).generateStreamWithTools(any(), any());
        verifyNoInteractions(mockLlmClient);
    }

    @Test
    void generateStreamWithTools_routesMockCorrectly() {
        RoutingLlmClient client = router("mock");
        doNothing().when(mockLlmClient).generateStreamWithTools(any(), any());

        client.generateStreamWithTools(LlmRequest.builder().build(), new StreamWithToolsListener() {
            @Override public void onContentToken(String token) {}
            @Override public void onToolCallStart(String toolName) {}
            @Override public void onDone(LlmResponse response) {}
        });

        verify(mockLlmClient).generateStreamWithTools(any(), any());
        verifyNoInteractions(openAiCompatibleLlmClient);
    }

    @Test
    void generateStreamWithTools_routesOllamaToOpenAiCompatible() {
        RoutingLlmClient client = router("ollama");
        doNothing().when(openAiCompatibleLlmClient).generateStreamWithTools(any(), any());

        client.generateStreamWithTools(LlmRequest.builder().build(), new StreamWithToolsListener() {
            @Override public void onContentToken(String token) {}
            @Override public void onToolCallStart(String toolName) {}
            @Override public void onDone(LlmResponse response) {}
        });

        verify(openAiCompatibleLlmClient).generateStreamWithTools(any(), any());
        verifyNoInteractions(mockLlmClient);
    }
}
