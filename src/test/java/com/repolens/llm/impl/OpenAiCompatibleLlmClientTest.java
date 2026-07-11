package com.repolens.llm.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.llm.LlmClientException;
import com.repolens.llm.config.LlmRuntimeConfig;
import com.repolens.llm.model.LlmRequest;
import com.repolens.llm.model.LlmResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

class OpenAiCompatibleLlmClientTest {

    @Test
    void generate_shouldCallChatCompletionsAndParseUsage() throws Exception {
        AtomicReference<String> pathRef = new AtomicReference<>();
        AtomicReference<String> authRef = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat/completions", exchange -> {
            pathRef.set(exchange.getRequestURI().getPath());
            authRef.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] responseBody = """
                    {
                      "choices": [
                        {
                          "message": {
                            "content": "这是来自真实 HTTP 客户端的回答。"
                          }
                        }
                      ],
                      "usage": {
                        "prompt_tokens": 123,
                        "completion_tokens": 45
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBody.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody);
            }
        });
        server.start();

        try {
            LlmRuntimeConfig cfg = runtimeConfig(
                    "http://localhost:" + server.getAddress().getPort() + "/", "test-api-key", "deepseek-chat", 3000);
            OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(new ObjectMapper(), cfg);

            LlmResponse response = client.generate(LlmRequest.builder()
                    .systemPrompt("system")
                    .userPrompt("user")
                    .build());

            Assertions.assertEquals("/chat/completions", pathRef.get());
            Assertions.assertEquals("Bearer test-api-key", authRef.get());
            Assertions.assertEquals("这是来自真实 HTTP 客户端的回答。", response.getContent());
            Assertions.assertEquals("deepseek-chat", response.getModelName());
            Assertions.assertEquals(123, response.getPromptTokens());
            Assertions.assertEquals(45, response.getCompletionTokens());
            Assertions.assertTrue(response.getCostMs() >= 0);
            Assertions.assertTrue(response.getSuccess());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void generate_shouldThrowConfigMissingWhenBaseUrlAbsent() {
        // 真正的必填配置是 base-url：缺失时立即抛 LLM_CONFIG_MISSING，不发起任何 HTTP 调用。
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(
                new ObjectMapper(), runtimeConfig("", "test-api-key", "deepseek-chat", 3000));

        LlmClientException ex = Assertions.assertThrows(
                LlmClientException.class,
                () -> client.generate(LlmRequest.builder().systemPrompt("system").userPrompt("user").build())
        );

        Assertions.assertEquals("LLM_CONFIG_MISSING", ex.getErrorCode());
        Assertions.assertTrue(ex.getMessage().contains("base-url"));
    }

    @Test
    void generate_shouldNotRequireApiKey() {
        // Ollama 等本地服务无需 api-key：只要 base-url 有效，就不应因缺 api-key 抛 LLM_CONFIG_MISSING。
        // 这里 base-url 指向一个不可达端口，因此会在真正 HTTP 调用阶段失败（LLM_CALL_FAILED 等），
        // 但这是连接错误而非配置缺失，正是期望的契约。
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(
                new ObjectMapper(), runtimeConfig("http://127.0.0.1:1/", "", "deepseek-chat", 1000));

        LlmClientException ex = Assertions.assertThrows(
                LlmClientException.class,
                () -> client.generate(LlmRequest.builder().systemPrompt("system").userPrompt("user").build())
        );

        // 关键断言：缺 api-key 不会被当成配置缺失。
        Assertions.assertNotEquals("LLM_CONFIG_MISSING", ex.getErrorCode());
    }

    /** 构造仅设置字段的运行时配置（不触发 DB init）。 */
    private static LlmRuntimeConfig runtimeConfig(String baseUrl, String apiKey, String modelName, int timeoutMs) {
        LlmRuntimeConfig cfg = new LlmRuntimeConfig(null);
        ReflectionTestUtils.setField(cfg, "baseUrl", baseUrl);
        ReflectionTestUtils.setField(cfg, "apiKey", apiKey);
        ReflectionTestUtils.setField(cfg, "modelName", modelName);
        ReflectionTestUtils.setField(cfg, "timeoutMs", timeoutMs);
        return cfg;
    }
}
