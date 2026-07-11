package com.repolens.llm.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.repolens.llm.LlmClient;
import com.repolens.llm.LlmClientException;
import com.repolens.llm.config.LlmRuntimeConfig;
import com.repolens.llm.model.LlmMessage;
import com.repolens.llm.model.LlmRequest;
import com.repolens.llm.model.LlmResponse;
import com.repolens.llm.model.ToolCall;
import com.repolens.llm.model.ToolDefinition;
import com.repolens.service.EgressPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * OpenAI-Compatible Chat Completions 客户端。
 * 这个实现负责对接 DeepSeek / OpenAI-Compatible 的文本对话接口：
 * 1. 从环境变量映射的 Spring 配置读取 base-url、api-key、model-name；
 * 2. 按 /chat/completions 协议组装请求；
 * 3. 解析 choices[0].message.content 与 usage；
 * 4. 调用失败时抛出带错误码的 LlmClientException，
 *    由上层 CodeAnswerService 统一做证据摘要降级。
 *
 * 安全边界：
 * - 不在日志、异常文本和返回值中输出 api-key；
 * - 不打印完整 prompt 或完整代码证据。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final int MIN_TIMEOUT_MS = 1000;
    /** 连接超时固定 10s：connectTimeout 属于 TCP 握手层面配置，与每请求的读取超时无关。*/
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final ObjectMapper objectMapper;
    private final LlmRuntimeConfig llmRuntimeConfig;

    /**
     * 出网策略网关（可选注入，测试环境无需注入，Spring 上下文自动注入）。
     * 在每次真实 HTTP 连接前调用 checkAndLog；null 时跳过（向后兼容）。
     */
    @Autowired(required = false)
    private EgressPolicy egressPolicy;

    /**
     * 复用单例 HttpClient：JDK17 HttpClient 非 AutoCloseable，selector 线程等 GC 回收，
     * 高频调用下每请求新建会导致线程/fd 持续堆积；复用单例可彻底规避该问题。
     * connectTimeout 固定 10s，请求读取超时通过 HttpRequest.Builder.timeout() 按请求配置。
     */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    /**
     * 真实调用 OpenAI-Compatible Chat Completions API。
     * 这里不直接吞异常，而是把配置问题、HTTP 问题、超时、解析问题区分开，
     * 让上层可以把 /chat/answer 稳定降级成“返回 RAG 证据摘要”。
     */
    @Override
    public LlmResponse generate(LlmRequest request) {
        if (request == null) {
            throw new LlmClientException("LLM_CALL_FAILED", "LLM request is empty");
        }
        String baseUrl = llmRuntimeConfig.getBaseUrl();
        String apiKey = llmRuntimeConfig.getApiKey();
        if (!StringUtils.hasText(baseUrl)) {
            throw new LlmClientException("LLM_CONFIG_MISSING", "LLM base-url is not configured");
        }

        String modelName = resolveModelName(request);
        int timeoutMs = resolveTimeoutMs(request);
        URI endpoint = resolveEndpoint(baseUrl);

        // 出网策略检查（P0 OPEN=只记录, P1 LOCAL_ONLY/ALLOWLIST=可能拦截）
        checkEgress(endpoint, modelName);

        long start = System.currentTimeMillis();
        try {
            // API Key 只出现在真实的 HTTP Header 中，不进入日志和错误文本。
            // Ollama 等本地服务不需要 api-key，故条件添加 Authorization 头。
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(endpoint)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json");
            buildAuthorizationHeader(apiKey).ifPresent(v -> requestBuilder.header("Authorization", v));
            HttpRequest httpRequest = requestBuilder
                    .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(request, modelName, false)))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            long costMs = System.currentTimeMillis() - start;
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw buildHttpError(response.statusCode(), response.body());
            }

            JsonNode root = readResponseBody(response.body());
            List<ToolCall> toolCalls = extractToolCalls(root);
            String finishReason = root.path("choices").path(0).path("finish_reason").asText(null);
            // 模型要求调用工具时 content 允许为空；只有“既无工具调用又无 content”才算解析失败。
            String content = extractAssistantContentNullable(root);
            if (!StringUtils.hasText(content) && toolCalls.isEmpty()) {
                throw new LlmClientException("LLM_RESPONSE_PARSE_ERROR", "LLM response missing both content and tool_calls");
            }
            int promptTokens = extractUsageValue(
                    root,
                    "prompt_tokens",
                    estimateTokens(safeText(request.getSystemPrompt()) + safeText(request.getUserPrompt()))
            );
            int completionTokens = extractUsageValue(root, "completion_tokens", estimateTokens(content));

            return LlmResponse.builder()
                    .content(content)
                    .modelName(modelName)
                    .promptTokens(promptTokens)
                    .completionTokens(completionTokens)
                    .costMs(costMs)
                    .success(true)
                    .errorCode(null)
                    .errorMessage(null)
                    .toolCalls(toolCalls)
                    .finishReason(finishReason)
                    .build();
        } catch (LlmClientException ex) {
            throw ex;
        } catch (HttpTimeoutException ex) {
            throw new LlmClientException("LLM_TIMEOUT", "LLM request timed out", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LlmClientException("LLM_CALL_FAILED", "LLM request was interrupted", ex);
        } catch (Exception ex) {
            throw new LlmClientException(
                    "LLM_CALL_FAILED",
                    "OpenAI-compatible call failed: " + trimError(ex.getMessage()),
                    ex
            );
        }
    }

    /**
     * 真正的流式实现：发同样的请求体但带 stream:true，按 SSE 行解析
     * choices[0].delta.content 逐 token 回调 onToken，遇到 data: [DONE] 用聚合文本
     * 构造最终 LlmResponse 回调 onDone。
     *
     * 稳健性优先：流式过程中的任何异常都不外抛，而是回落到非流式 {@link #generate(LlmRequest)}
     * 一次性推送（仍逐段可见）。仅当连非流式也失败时才抛 LlmClientException，
     * 交由上层 CodeAnswerService 做证据摘要降级。
     */
    @Override
    public void generateStream(LlmRequest request,
                               Consumer<String> onToken,
                               Consumer<LlmResponse> onDone) {
        if (request == null) {
            throw new LlmClientException("LLM_CALL_FAILED", "LLM request is empty");
        }
        String baseUrl = llmRuntimeConfig.getBaseUrl();
        String apiKey = llmRuntimeConfig.getApiKey();
        // 未配置 base-url 时不尝试流式，直接走默认一次性契约（其内部再抛配置缺失）。
        if (!StringUtils.hasText(baseUrl)) {
            throw new LlmClientException("LLM_CONFIG_MISSING", "LLM base-url is not configured");
        }
        // 带工具的 agentic 请求不适合逐 token 流式（要解析 tool_calls），退回一次性契约。
        if (request.getTools() != null && !request.getTools().isEmpty()) {
            LlmClient.super.generateStream(request, onToken, onDone);
            return;
        }

        String modelName = resolveModelName(request);
        int timeoutMs = resolveTimeoutMs(request);
        URI endpoint = resolveEndpoint(baseUrl);

        // 出网策略检查（流式与非流式共用同一 endpoint，统一检查）
        checkEgress(endpoint, modelName);

        long start = System.currentTimeMillis();
        StringBuilder accumulated = new StringBuilder();
        boolean sawDone = false;
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(endpoint)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream");
            buildAuthorizationHeader(apiKey).ifPresent(v -> requestBuilder.header("Authorization", v));
            HttpRequest httpRequest = requestBuilder
                    .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(request, modelName, true)))
                    .build();

            HttpResponse<InputStream> response =
                    httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                // 读取并关闭 body：非 2xx 时 body InputStream 不进 try-with-resources，必须手动关闭，
                // 否则 selector 线程泄漏（高频调用下文件描述符堆积）。
                // bodySnippet 直接作为 responseBody 传给 buildHttpError，使 extractProviderErrorMessage
                // 能够正确 JSON 解析并提取 error.message（不再拼前缀字符串，避免 JSON 解析失败后摘要丢失）。
                String bodySnippet = readBodySnippet(response.body());
                if (!bodySnippet.isEmpty()) {
                    log.warn("generateStream: non-2xx response (status={}), body snippet: {}",
                            response.statusCode(), bodySnippet);
                }
                throw buildHttpError(response.statusCode(), bodySnippet);
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        continue;
                    }
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring("data:".length()).trim();
                    if ("[DONE]".equals(data)) {
                        sawDone = true;
                        break;
                    }
                    String delta = extractDeltaContent(data);
                    if (delta != null && !delta.isEmpty()) {
                        accumulated.append(delta);
                        onToken.accept(delta);
                    }
                }
            }

            long costMs = System.currentTimeMillis() - start;
            String content = accumulated.toString();
            // 流没给任何内容也没看到 [DONE]：视为异常，走一次性降级重试。
            if (content.isEmpty() && !sawDone) {
                throw new LlmClientException("LLM_RESPONSE_PARSE_ERROR", "stream produced no content");
            }
            int promptTokens = estimateTokens(safeText(request.getSystemPrompt()) + safeText(request.getUserPrompt()));
            LlmResponse finalResponse = LlmResponse.builder()
                    .content(content)
                    .modelName(modelName)
                    .promptTokens(promptTokens)
                    .completionTokens(estimateTokens(content))
                    .costMs(costMs)
                    .success(true)
                    .errorCode(null)
                    .errorMessage(null)
                    .finishReason("stop")
                    .build();
            onDone.accept(finalResponse);
        } catch (Exception streamError) {
            // 流式失败：回落到非流式一次性推送。若已经推了部分 token，就不再重复整段，
            // 只补齐剩余（这里简单起见按整段重发的风险由“未推过任何 token”守卫）。
            log.warn("LLM stream failed, fall back to non-streaming generate, reason={}",
                    trimError(streamError.getMessage()));
            if (accumulated.length() > 0) {
                // 已经流出过部分内容，无法安全续接，直接用已累计内容作为最终结果，避免重复整段。
                String content = accumulated.toString();
                onDone.accept(LlmResponse.builder()
                        .content(content)
                        .modelName(modelName)
                        .promptTokens(estimateTokens(safeText(request.getSystemPrompt()) + safeText(request.getUserPrompt())))
                        .completionTokens(estimateTokens(content))
                        .costMs(System.currentTimeMillis() - start)
                        .success(true)
                        .finishReason("stop")
                        .build());
                return;
            }
            // 尚未推送任何 token：安全地走非流式 generate 再一次性推送（可能抛，交上层降级）。
            LlmResponse response = generate(request);
            if (response != null && response.getContent() != null && !response.getContent().isEmpty()) {
                onToken.accept(response.getContent());
            }
            onDone.accept(response);
        }
    }

    /**
     * 带工具的真流式：stream=true 且保留 tools，解析 delta.tool_calls 增量片段
     * （index→id/name/arguments 逐块拼接），与 delta.content 并行处理，流结束后组装出
     * 与非流式等价的 LlmResponse（含完整 toolCalls）。
     *
     * 稳健性：任何流式异常都回落到非流式 {@link #generate}，不外抛。
     */
    @Override
    public void generateStreamWithTools(LlmRequest request, com.repolens.llm.StreamWithToolsListener listener) {
        if (request == null) {
            throw new LlmClientException("LLM_CALL_FAILED", "LLM request is empty");
        }
        String baseUrl = llmRuntimeConfig.getBaseUrl();
        String apiKey = llmRuntimeConfig.getApiKey();
        if (!StringUtils.hasText(baseUrl)) {
            throw new LlmClientException("LLM_CONFIG_MISSING", "LLM base-url is not configured");
        }

        String modelName = resolveModelName(request);
        int timeoutMs = resolveTimeoutMs(request);
        URI endpoint = resolveEndpoint(baseUrl);

        // 出网策略检查（stream-with-tools 路径）
        checkEgress(endpoint, modelName);

        long start = System.currentTimeMillis();
        // 按 index 积累 tool_call 片段（OpenAI 流格式：每个 index 独立的 partial 对象）。
        Map<Integer, PartialToolCall> partialCalls = new LinkedHashMap<>();
        StringBuilder contentAccumulated = new StringBuilder();
        boolean sawDone = false;

        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(endpoint)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream");
            buildAuthorizationHeader(apiKey).ifPresent(v -> requestBuilder.header("Authorization", v));
            // stream=true，tools 保留（不再强制退回非流式）。
            HttpRequest httpRequest = requestBuilder
                    .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(request, modelName, true)))
                    .build();

            HttpResponse<InputStream> response =
                    httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                // 读取并关闭 body：与 generateStream 一致，避免 InputStream 泄漏。
                // bodySnippet 直接传给 buildHttpError（无前缀），使 JSON 解析能正确提取 error.message。
                String bodySnippet = readBodySnippet(response.body());
                if (!bodySnippet.isEmpty()) {
                    log.warn("generateStreamWithTools: non-2xx response (status={}), body snippet: {}",
                            response.statusCode(), bodySnippet);
                }
                throw buildHttpError(response.statusCode(), bodySnippet);
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        continue;
                    }
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring("data:".length()).trim();
                    if ("[DONE]".equals(data)) {
                        sawDone = true;
                        break;
                    }
                    // 内容 delta
                    String contentDelta = extractDeltaContent(data);
                    if (contentDelta != null && !contentDelta.isEmpty()) {
                        contentAccumulated.append(contentDelta);
                        listener.onContentToken(contentDelta);
                    }
                    // 工具调用 delta（增量拼接 arguments）
                    parseToolCallsDelta(data, partialCalls, listener);
                }
            }

            long costMs = System.currentTimeMillis() - start;
            String content = contentAccumulated.toString();
            List<ToolCall> toolCalls = assembleToolCalls(partialCalls);

            if (content.isEmpty() && toolCalls.isEmpty() && !sawDone) {
                throw new LlmClientException("LLM_RESPONSE_PARSE_ERROR",
                        "stream-with-tools produced no content and no tool_calls");
            }

            int promptTokens = estimateTokens(safeText(request.getSystemPrompt()) + safeText(request.getUserPrompt()));
            LlmResponse finalResponse = LlmResponse.builder()
                    .content(content)
                    .modelName(modelName)
                    .promptTokens(promptTokens)
                    .completionTokens(estimateTokens(content))
                    .costMs(costMs)
                    .success(true)
                    .toolCalls(toolCalls)
                    .finishReason(toolCalls.isEmpty() ? "stop" : "tool_calls")
                    .build();
            listener.onDone(finalResponse);

        } catch (Exception streamError) {
            log.warn("LLM stream-with-tools failed, fall back to non-streaming generate, reason={}",
                    trimError(streamError.getMessage()));
            // 回落非流式（保留 tools 解析，generate 本身支持 tool_calls）。
            try {
                LlmResponse fallback = generate(request);
                if (fallback != null) {
                    if (fallback.getToolCalls() != null) {
                        for (ToolCall tc : fallback.getToolCalls()) {
                            if (tc.getName() != null && !tc.getName().isEmpty()) {
                                listener.onToolCallStart(tc.getName());
                            }
                        }
                    }
                    if (fallback.getContent() != null && !fallback.getContent().isEmpty()) {
                        listener.onContentToken(fallback.getContent());
                    }
                }
                listener.onDone(fallback);
            } catch (Exception fallbackError) {
                // generate 也失败：把异常包成 LlmClientException 上抛，交上层降级。
                if (fallbackError instanceof LlmClientException lce) {
                    throw lce;
                }
                throw new LlmClientException("LLM_CALL_FAILED",
                        "stream-with-tools and fallback both failed: " + trimError(fallbackError.getMessage()),
                        fallbackError);
            }
        }
    }

    /**
     * 读取 InputStream 的前 500 字节并关闭流，返回字符串摘要。
     * 用于非 2xx 流式响应：既给出可读错误信息，又避免 InputStream 泄漏（不进 try-with-resources 时必须手动关闭）。
     */
    private String readBodySnippet(InputStream body) {
        if (body == null) {
            return "";
        }
        try (InputStream is = body) {
            byte[] buf = new byte[512];
            int n = is.read(buf);
            if (n <= 0) {
                return "";
            }
            String text = new String(buf, 0, n, StandardCharsets.UTF_8);
            return text.length() > 500 ? text.substring(0, 500) : text;
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * 解析单条 SSE data 行里的 choices[0].delta.tool_calls[]，
     * 按 index 累积到 partialCalls，name 首次可用时回调 listener.onToolCallStart。
     */
    private void parseToolCallsDelta(String data, Map<Integer, PartialToolCall> partialCalls,
                                      com.repolens.llm.StreamWithToolsListener listener) {
        try {
            JsonNode node = objectMapper.readTree(data);
            JsonNode toolCallsNode = node.path("choices").path(0).path("delta").path("tool_calls");
            if (!toolCallsNode.isArray() || toolCallsNode.isEmpty()) {
                return;
            }
            for (JsonNode tcNode : toolCallsNode) {
                int index = tcNode.path("index").asInt(0);
                PartialToolCall partial = partialCalls.computeIfAbsent(index, k -> new PartialToolCall());
                if (tcNode.path("id").isTextual()) {
                    partial.id = tcNode.path("id").asText();
                }
                JsonNode funcNode = tcNode.path("function");
                if (funcNode.path("name").isTextual()) {
                    String name = funcNode.path("name").asText();
                    if (!name.isEmpty()) {
                        if (partial.name == null) {
                            partial.name = name;
                        } else {
                            partial.name = partial.name + name; // name 理论上一次到位，但防御性拼接
                        }
                        if (!partial.nameNotified && StringUtils.hasText(partial.name)) {
                            partial.nameNotified = true;
                            listener.onToolCallStart(partial.name);
                        }
                    }
                }
                if (funcNode.path("arguments").isTextual()) {
                    partial.arguments.append(funcNode.path("arguments").asText());
                }
            }
        } catch (Exception ex) {
            // 忽略单帧解析失败，不中断流。
        }
    }

    /** 把所有 PartialToolCall 组装为完整 ToolCall 列表。 */
    private List<ToolCall> assembleToolCalls(Map<Integer, PartialToolCall> partialCalls) {
        if (partialCalls.isEmpty()) {
            return List.of();
        }
        List<ToolCall> result = new ArrayList<>();
        for (PartialToolCall partial : partialCalls.values()) {
            if (!StringUtils.hasText(partial.name)) {
                continue;
            }
            Map<String, Object> arguments = parseArguments(partial.arguments.toString());
            result.add(ToolCall.builder()
                    .id(partial.id)
                    .name(partial.name)
                    .arguments(arguments)
                    .build());
        }
        return result;
    }

    /** 解析单条 SSE data 行的 choices[0].delta.content，缺失/解析失败返回 null。 */
    private String extractDeltaContent(String data) {
        try {
            JsonNode node = objectMapper.readTree(data);
            JsonNode contentNode = node.path("choices").path(0).path("delta").path("content");
            return contentNode.isTextual() ? contentNode.asText() : null;
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * base-url 只配置到服务根路径，避免要求用户手工重复拼接 /chat/completions。
     * 例如：
     * - https://api.deepseek.com -> https://api.deepseek.com/chat/completions
     * - https://api.deepseek.com/ -> https://api.deepseek.com/chat/completions
     * - https://api.deepseek.com/chat/completions -> 保持不变
     */
    private URI resolveEndpoint(String configuredBaseUrl) {
        String normalized = configuredBaseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith(CHAT_COMPLETIONS_PATH)) {
            return URI.create(normalized);
        }
        return URI.create(normalized + CHAT_COMPLETIONS_PATH);
    }

    private String resolveModelName(LlmRequest request) {
        String modelName = StringUtils.hasText(request.getModelName())
                ? request.getModelName()
                : llmRuntimeConfig.getModelName();
        if (!StringUtils.hasText(modelName)) {
            throw new LlmClientException("LLM_CONFIG_MISSING", "LLM model-name is not configured");
        }
        return modelName.trim();
    }

    private int resolveTimeoutMs(LlmRequest request) {
        int configured = request.getTimeoutMs() == null || request.getTimeoutMs() <= 0
                ? llmRuntimeConfig.getTimeoutMs()
                : request.getTimeoutMs();
        return Math.max(configured, MIN_TIMEOUT_MS);
    }

    private String buildRequestBody(LlmRequest request, String modelName, boolean stream) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", modelName);
            root.put("temperature", request.getTemperature() == null ? 0.2d : request.getTemperature());
            root.put("stream", stream);

            ArrayNode messages = root.putArray("messages");
            if (request.getMessages() != null && !request.getMessages().isEmpty()) {
                // agentic 多轮：直接按对话历史发送（含 assistant.tool_calls 与 role=tool 的结果）。
                for (LlmMessage message : request.getMessages()) {
                    messages.add(toMessageNode(message));
                }
            } else {
                // 单轮问答：保持原有 system + user 形态，向后兼容。
                messages.addObject()
                        .put("role", "system")
                        .put("content", safeText(request.getSystemPrompt()));
                messages.addObject()
                        .put("role", "user")
                        .put("content", safeText(request.getUserPrompt()));
            }

            if (request.getTools() != null && !request.getTools().isEmpty()) {
                ArrayNode tools = root.putArray("tools");
                for (ToolDefinition tool : request.getTools()) {
                    ObjectNode toolNode = tools.addObject();
                    toolNode.put("type", "function");
                    ObjectNode function = toolNode.putObject("function");
                    function.put("name", tool.getName());
                    function.put("description", safeText(tool.getDescription()));
                    if (tool.getParameters() != null) {
                        function.set("parameters", objectMapper.valueToTree(tool.getParameters()));
                    }
                }
                root.put("tool_choice", "auto");
            }

            return objectMapper.writeValueAsString(root);
        } catch (Exception ex) {
            throw new LlmClientException(
                    "LLM_CALL_FAILED",
                    "Failed to serialize LLM request body: " + trimError(ex.getMessage()),
                    ex
            );
        }
    }

    /** 把一条 LlmMessage 转成 OpenAI-Compatible messages[] 元素。 */
    private ObjectNode toMessageNode(LlmMessage message) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("role", message.getRole());
        node.put("content", safeText(message.getContent()));
        if ("tool".equals(message.getRole()) && StringUtils.hasText(message.getToolCallId())) {
            node.put("tool_call_id", message.getToolCallId());
        }
        if (message.getToolCalls() != null && !message.getToolCalls().isEmpty()) {
            ArrayNode toolCalls = node.putArray("tool_calls");
            for (ToolCall call : message.getToolCalls()) {
                ObjectNode callNode = toolCalls.addObject();
                callNode.put("id", call.getId());
                callNode.put("type", "function");
                ObjectNode function = callNode.putObject("function");
                function.put("name", call.getName());
                try {
                    function.put("arguments", objectMapper.writeValueAsString(
                            call.getArguments() == null ? Map.of() : call.getArguments()));
                } catch (Exception ex) {
                    function.put("arguments", "{}");
                }
            }
        }
        return node;
    }

    private JsonNode readResponseBody(String responseBody) {
        try {
            return objectMapper.readTree(responseBody);
        } catch (Exception ex) {
            throw new LlmClientException("LLM_RESPONSE_PARSE_ERROR", "Failed to parse LLM response body", ex);
        }
    }

    private String extractAssistantContent(JsonNode root) {
        JsonNode messageContent = root.path("choices").path(0).path("message").path("content");
        if (messageContent.isMissingNode() || messageContent.isNull()) {
            throw new LlmClientException("LLM_RESPONSE_PARSE_ERROR", "LLM response missing choices[0].message.content");
        }
        if (messageContent.isTextual()) {
            return messageContent.asText("");
        }
        if (messageContent.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode item : messageContent) {
                if (item.isTextual()) {
                    builder.append(item.asText());
                } else if (item.path("text").isTextual()) {
                    builder.append(item.path("text").asText());
                }
            }
            return builder.toString();
        }
        return messageContent.toString();
    }

    /**
     * 提取 content，但允许缺失/为空（模型改用 tool_calls 时 content 为 null）。
     * 与 extractAssistantContent 的区别：不抛异常，缺失返回空串。
     */
    private String extractAssistantContentNullable(JsonNode root) {
        JsonNode messageContent = root.path("choices").path(0).path("message").path("content");
        if (messageContent.isMissingNode() || messageContent.isNull()) {
            return "";
        }
        if (messageContent.isTextual()) {
            return messageContent.asText("");
        }
        if (messageContent.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode item : messageContent) {
                if (item.isTextual()) {
                    builder.append(item.asText());
                } else if (item.path("text").isTextual()) {
                    builder.append(item.path("text").asText());
                }
            }
            return builder.toString();
        }
        return messageContent.toString();
    }

    /**
     * 解析 choices[0].message.tool_calls[]。
     * arguments 是 JSON 字符串，解析成 Map 供 ToolInvokeService 取参；解析失败则置空 Map。
     */
    private List<ToolCall> extractToolCalls(JsonNode root) {
        JsonNode toolCallsNode = root.path("choices").path(0).path("message").path("tool_calls");
        if (!toolCallsNode.isArray() || toolCallsNode.isEmpty()) {
            return List.of();
        }
        List<ToolCall> result = new ArrayList<>();
        for (JsonNode node : toolCallsNode) {
            String id = node.path("id").asText(null);
            JsonNode function = node.path("function");
            String name = function.path("name").asText(null);
            if (!StringUtils.hasText(name)) {
                continue;
            }
            Map<String, Object> arguments = parseArguments(function.path("arguments").asText("{}"));
            result.add(ToolCall.builder().id(id).name(name).arguments(arguments).build());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(String rawArguments) {
        if (!StringUtils.hasText(rawArguments)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(rawArguments, Map.class);
        } catch (Exception ex) {
            return new LinkedHashMap<>();
        }
    }

    private int extractUsageValue(JsonNode root, String fieldName, int fallback) {
        JsonNode usageNode = root.path("usage").path(fieldName);
        return usageNode.isInt() || usageNode.isLong() ? usageNode.asInt() : fallback;
    }

    private LlmClientException buildHttpError(int statusCode, String responseBody) {
        String providerMessage = extractProviderErrorMessage(responseBody);
        String message = "LLM HTTP error, status=" + statusCode;
        if (StringUtils.hasText(providerMessage)) {
            message = message + ", reason=" + providerMessage;
        }
        return new LlmClientException("LLM_HTTP_ERROR", message);
    }

    /**
     * 只提取 provider 返回的简短错误摘要，不直接暴露完整响应体。
     * 这样既能给出可读错误，也避免把过长文本或潜在敏感信息带入日志。
     */
    private String extractProviderErrorMessage(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode errorNode = root.path("error");
            if (errorNode.path("message").isTextual()) {
                return trimError(errorNode.path("message").asText());
            }
            if (errorNode.path("code").isTextual()) {
                return trimError(errorNode.path("code").asText());
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private int estimateTokens(String text) {
        if (!StringUtils.hasText(text)) {
            return 0;
        }
        return Math.max(1, text.length() / 4);
    }

    private String safeText(String text) {
        return text == null ? "" : text;
    }

    /**
     * 构造 Authorization 头。api-key 为空（如本地 Ollama）时返回空，不发送该头。
     * Package-private and static so tests can call it directly without a Spring context.
     */
    static java.util.Optional<String> buildAuthorizationHeader(String apiKey) {
        return StringUtils.hasText(apiKey)
                ? java.util.Optional.of("Bearer " + apiKey.trim())
                : java.util.Optional.empty();
    }

    private String trimError(String error) {
        if (!StringUtils.hasText(error)) {
            return "unknown";
        }
        String trimmed = error.trim().replaceAll("\\s+", " ");
        return trimmed.length() > 300 ? trimmed.substring(0, 300) : trimmed;
    }

    /**
     * 出网策略前置检查（EgressPolicy 为 null 时跳过，兼容未注入的测试场景）。
     *
     * @param endpoint  已解析的目标 URI
     * @param modelName 当前模型名（记入审计日志）
     */
    private void checkEgress(URI endpoint, String modelName) {
        if (egressPolicy == null || endpoint == null) {
            return;
        }
        try {
            String host = endpoint.getHost();
            if (host == null || host.isBlank()) {
                return;
            }
            int port = endpoint.getPort();
            if (port <= 0) {
                port = "https".equalsIgnoreCase(endpoint.getScheme()) ? 443 : 80;
            }
            egressPolicy.checkAndLog(host, port, com.repolens.domain.entity.EgressLogEntity.PURPOSE_LLM, modelName);
        } catch (com.repolens.common.exception.BizException blocked) {
            throw blocked; // 直接上抛，让调用方收到 403
        } catch (Exception unexpected) {
            // 策略检查本身意外失败：失败安全，不阻断 LLM 调用
            log.warn("EgressPolicy check failed unexpectedly (fail-safe, allowing), err={}", unexpected.getMessage());
        }
    }

    /** 流式 tool_calls 增量积累器（按 index 维度）。 */
    private static class PartialToolCall {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
        /** name 已回调过 onToolCallStart（避免重复通知）。 */
        boolean nameNotified;
    }
}
