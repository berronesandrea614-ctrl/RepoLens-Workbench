package com.repolens.llm.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.llm.StreamWithToolsListener;
import com.repolens.llm.config.LlmRuntimeConfig;
import com.repolens.llm.model.LlmRequest;
import com.repolens.llm.model.LlmResponse;
import com.repolens.llm.model.ToolCall;
import com.repolens.llm.model.ToolDefinition;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 验证 OpenAiCompatibleLlmClient.generateStreamWithTools 的新功能：
 * - delta.tool_calls 多片段 arguments 增量拼接 → 组装出完整 ToolCall
 * - 纯内容答案轮（无 tool_calls）的 content token 流式回调
 * - RoutingLlmClient 转发 generateStreamWithTools（独立测试，见 RoutingLlmClientStreamForwardTest）
 */
class OpenAiCompatibleLlmClientStreamToolsTest {

    // -----------------------------------------------------------------------
    // TC-1: delta.tool_calls 多片段 arguments 拼接 → 完整 JSON
    // -----------------------------------------------------------------------

    @Test
    void generateStreamWithTools_assemblesIncrementalToolCallArguments() throws Exception {
        // SSE 流：3 个 data 帧携带同一 tool_call 的 arguments 片段，组装后应等于完整 JSON
        String sseBody = """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"searchCodeChunks","arguments":""}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\"query\\""}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":":\\"用户创建\\","}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"topK\\":5}"}}]}}]}

                data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """;

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] body = sseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();

        try {
            LlmRuntimeConfig cfg = runtimeConfig("http://localhost:" + server.getAddress().getPort(), "key", "model", 3000);
            OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(new ObjectMapper(), cfg);

            // 请求带 tools（否则走普通 generateStream）
            LlmRequest request = LlmRequest.builder()
                    .messages(List.of())
                    .tools(List.of(ToolDefinition.builder().name("searchCodeChunks")
                            .description("search")
                            .parameters(Map.of("type", "object"))
                            .build()))
                    .build();

            List<String> toolCallNames = new ArrayList<>();
            AtomicReference<LlmResponse> done = new AtomicReference<>();

            client.generateStreamWithTools(request, new StreamWithToolsListener() {
                @Override
                public void onContentToken(String token) { /* 无 content 帧 */ }

                @Override
                public void onToolCallStart(String toolName) {
                    toolCallNames.add(toolName);
                }

                @Override
                public void onDone(LlmResponse response) {
                    done.set(response);
                }
            });

            LlmResponse response = done.get();
            Assertions.assertNotNull(response);
            // 1) onToolCallStart 被触发，name 正确
            Assertions.assertEquals(1, toolCallNames.size());
            Assertions.assertEquals("searchCodeChunks", toolCallNames.get(0));

            // 2) 最终 toolCalls 非空
            Assertions.assertNotNull(response.getToolCalls());
            Assertions.assertEquals(1, response.getToolCalls().size());
            ToolCall tc = response.getToolCalls().get(0);
            Assertions.assertEquals("searchCodeChunks", tc.getName());
            Assertions.assertEquals("call-1", tc.getId());

            // 3) arguments 多片段拼出完整 JSON，query 和 topK 均可解析
            Map<String, Object> args = tc.getArguments();
            Assertions.assertNotNull(args);
            Assertions.assertEquals("用户创建", args.get("query"));
            Assertions.assertEquals(5, ((Number) args.get("topK")).intValue());

            // 4) content 为空（纯 tool_calls 轮）
            Assertions.assertTrue(response.getContent() == null || response.getContent().isEmpty());

        } finally {
            server.stop(0);
        }
    }

    // -----------------------------------------------------------------------
    // TC-2: 最终答案轮（无 tool_calls）— content tokens 实时回调
    // -----------------------------------------------------------------------

    @Test
    void generateStreamWithTools_noToolCalls_streamsContentTokens() throws Exception {
        String sseBody = """
                data: {"choices":[{"delta":{"content":"Hello"}}]}

                data: {"choices":[{"delta":{"content":", "}}]}

                data: {"choices":[{"delta":{"content":"world"}}]}

                data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

                data: [DONE]

                """;

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] body = sseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();

        try {
            LlmRuntimeConfig cfg = runtimeConfig("http://localhost:" + server.getAddress().getPort(), "key", "model", 3000);
            OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(new ObjectMapper(), cfg);

            // 不带工具的请求也能调 generateStreamWithTools（应走 content 路径）
            LlmRequest request = LlmRequest.builder()
                    .messages(List.of())
                    .build();

            List<String> tokens = new ArrayList<>();
            AtomicReference<LlmResponse> done = new AtomicReference<>();

            client.generateStreamWithTools(request, new StreamWithToolsListener() {
                @Override
                public void onContentToken(String token) {
                    tokens.add(token);
                }

                @Override
                public void onToolCallStart(String toolName) {
                    Assertions.fail("Unexpected tool call start: " + toolName);
                }

                @Override
                public void onDone(LlmResponse response) {
                    done.set(response);
                }
            });

            Assertions.assertEquals(List.of("Hello", ", ", "world"), tokens);

            LlmResponse response = done.get();
            Assertions.assertNotNull(response);
            Assertions.assertEquals("Hello, world", response.getContent());
            Assertions.assertTrue(response.getToolCalls() == null || response.getToolCalls().isEmpty());

        } finally {
            server.stop(0);
        }
    }

    // -----------------------------------------------------------------------
    // TC-3: 多个工具调用（不同 index）正确分离
    // -----------------------------------------------------------------------

    @Test
    void generateStreamWithTools_multipleToolCalls_assembledByIndex() throws Exception {
        // 两个并行工具调用（index=0 和 index=1）
        String sseBody = """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"id-0","function":{"name":"toolA","arguments":""}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":1,"id":"id-1","function":{"name":"toolB","arguments":""}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\"a\\":1}"}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"arguments":"{\\"b\\":2}"}}]}}]}

                data: [DONE]

                """;

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] body = sseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();

        try {
            LlmRuntimeConfig cfg = runtimeConfig("http://localhost:" + server.getAddress().getPort(), "key", "model", 3000);
            OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(new ObjectMapper(), cfg);

            LlmRequest request = LlmRequest.builder().messages(List.of()).build();
            List<String> startedNames = new ArrayList<>();
            AtomicReference<LlmResponse> done = new AtomicReference<>();

            client.generateStreamWithTools(request, new StreamWithToolsListener() {
                @Override
                public void onContentToken(String token) {}

                @Override
                public void onToolCallStart(String toolName) {
                    startedNames.add(toolName);
                }

                @Override
                public void onDone(LlmResponse response) {
                    done.set(response);
                }
            });

            LlmResponse response = done.get();
            Assertions.assertNotNull(response);
            Assertions.assertEquals(2, response.getToolCalls().size());

            // 验证两个工具名和参数
            ToolCall tc0 = response.getToolCalls().stream().filter(t -> "toolA".equals(t.getName())).findFirst().orElseThrow();
            ToolCall tc1 = response.getToolCalls().stream().filter(t -> "toolB".equals(t.getName())).findFirst().orElseThrow();

            Assertions.assertEquals("id-0", tc0.getId());
            Assertions.assertEquals(1, ((Number) tc0.getArguments().get("a")).intValue());

            Assertions.assertEquals("id-1", tc1.getId());
            Assertions.assertEquals(2, ((Number) tc1.getArguments().get("b")).intValue());

            // onToolCallStart 两次
            Assertions.assertEquals(2, startedNames.size());
            Assertions.assertTrue(startedNames.contains("toolA"));
            Assertions.assertTrue(startedNames.contains("toolB"));

        } finally {
            server.stop(0);
        }
    }

    private static LlmRuntimeConfig runtimeConfig(String baseUrl, String apiKey, String modelName, int timeoutMs) {
        LlmRuntimeConfig cfg = new LlmRuntimeConfig(null);
        ReflectionTestUtils.setField(cfg, "baseUrl", baseUrl);
        ReflectionTestUtils.setField(cfg, "apiKey", apiKey);
        ReflectionTestUtils.setField(cfg, "modelName", modelName);
        ReflectionTestUtils.setField(cfg, "timeoutMs", timeoutMs);
        return cfg;
    }
}
