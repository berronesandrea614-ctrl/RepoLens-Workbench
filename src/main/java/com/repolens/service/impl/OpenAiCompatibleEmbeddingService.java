package com.repolens.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.repolens.domain.entity.EgressLogEntity;
import com.repolens.service.EgressPolicy;
import com.repolens.service.EmbeddingClientException;
import com.repolens.service.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI-Compatible Embeddings API 对接实现。
 * 当前阶段只负责：
 * 1. 从环境变量映射的 Spring 配置中读取 base-url / api-key / model-name；
 * 2. 调用 POST {baseUrl}/embeddings；
 * 3. 解析 data[].embedding；
 * 4. 校验返回数量和向量维度；
 * 5. 在失败时抛出带 errorCode 的 EmbeddingClientException。
 *
 * 安全边界：
 * - api-key 只进入真实 HTTP Header，不写日志；
 * - 不打印完整 input 文本，只记录 batchSize、model、耗时；
 * - 不把 provider 原始响应完整打到日志里。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiCompatibleEmbeddingService implements EmbeddingService {

    private static final String EMBEDDINGS_PATH = "/embeddings";
    private static final int MIN_TIMEOUT_MS = 1000;
    /** connectTimeout 固定 10s；请求级读取超时通过 HttpRequest.Builder.timeout() 按请求配置。*/
    private static final java.time.Duration CONNECT_TIMEOUT = java.time.Duration.ofSeconds(10);

    private final ObjectMapper objectMapper;

    /** 出网策略网关（可选注入）。 */
    @Autowired(required = false)
    private EgressPolicy egressPolicy;

    /**
     * 复用单例 HttpClient，避免高频 embedding 调用时 selector 线程/fd 堆积。
     * 字段初始化器在字段注入（@Value）之前运行，因此 connectTimeout 使用固定常量而非 @Value 字段。
     */
    private final java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    @Value("${repolens.embedding.base-url:}")
    private String baseUrl;

    @Value("${repolens.embedding.api-key:}")
    private String apiKey;

    @Value("${repolens.embedding.model-name:mock-embedding}")
    private String defaultModelName;

    @Value("${repolens.embedding.dimension:384}")
    private int embeddingDimension;

    @Value("${repolens.embedding.timeout-ms:15000}")
    private int defaultTimeoutMs;

    @Override
    public float[] embed(String text) {
        List<float[]> result = embedBatch(List.of(text == null ? "" : text));
        if (result.isEmpty()) {
            throw new EmbeddingClientException("EMBEDDING_RESPONSE_PARSE_ERROR", "Embedding response is empty");
        }
        return result.get(0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        if (!StringUtils.hasText(baseUrl)) {
            throw new EmbeddingClientException("EMBEDDING_CONFIG_MISSING", "Embedding base-url is not configured");
        }
        if (!StringUtils.hasText(apiKey)) {
            throw new EmbeddingClientException("EMBEDDING_CONFIG_MISSING", "Embedding api-key is not configured");
        }
        if (!StringUtils.hasText(defaultModelName)) {
            throw new EmbeddingClientException("EMBEDDING_CONFIG_MISSING", "Embedding model-name is not configured");
        }
        if (embeddingDimension <= 0) {
            throw new EmbeddingClientException("EMBEDDING_DIMENSION_MISMATCH", "Embedding dimension config is invalid");
        }

        URI endpoint = resolveEndpoint(baseUrl);
        int timeoutMs = Math.max(defaultTimeoutMs, MIN_TIMEOUT_MS);

        // 出网策略检查（EMBEDDING 路径）
        checkEgressEmbedding(endpoint);

        List<String> normalizedInputs = texts.stream()
                .map(text -> text == null ? "" : text)
                .toList();

        long start = System.currentTimeMillis();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(endpoint)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    // api-key 只出现在真实调用的 Header 中，不进入日志和错误消息。
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(normalizedInputs)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long costMs = System.currentTimeMillis() - start;
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw buildHttpError(response.statusCode(), response.body());
            }

            JsonNode root = readResponseBody(response.body());
            List<float[]> embeddings = extractEmbeddings(root, normalizedInputs.size());
            extractUsageValue(root, "prompt_tokens");
            extractUsageValue(root, "total_tokens");

            log.info("Embedding request completed, modelName={}, batchSize={}, dimension={}, costMs={}",
                    defaultModelName.trim(), normalizedInputs.size(), embeddingDimension, costMs);
            return embeddings;
        } catch (EmbeddingClientException ex) {
            throw ex;
        } catch (HttpTimeoutException ex) {
            throw new EmbeddingClientException("EMBEDDING_TIMEOUT", "Embedding request timed out", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new EmbeddingClientException("EMBEDDING_CALL_FAILED", "Embedding request was interrupted", ex);
        } catch (Exception ex) {
            throw new EmbeddingClientException(
                    "EMBEDDING_CALL_FAILED",
                    "OpenAI-compatible embedding call failed: " + trimError(ex.getMessage()),
                    ex
            );
        }
    }

    /**
     * base-url 只配置到 provider 根路径，避免要求调用方手工重复拼接 /embeddings。
     * 例如：
     * - https://provider.example/v1 -> https://provider.example/v1/embeddings
     * - https://provider.example/v1/ -> https://provider.example/v1/embeddings
     * - https://provider.example/v1/embeddings -> 保持不变
     */
    private URI resolveEndpoint(String configuredBaseUrl) {
        String normalized = configuredBaseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith(EMBEDDINGS_PATH)) {
            return URI.create(normalized);
        }
        return URI.create(normalized + EMBEDDINGS_PATH);
    }

    private String buildRequestBody(List<String> texts) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", defaultModelName.trim());
            ArrayNode inputNode = root.putArray("input");
            for (String text : texts) {
                inputNode.add(text);
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception ex) {
            throw new EmbeddingClientException(
                    "EMBEDDING_CALL_FAILED",
                    "Failed to serialize embedding request body: " + trimError(ex.getMessage()),
                    ex
            );
        }
    }

    private JsonNode readResponseBody(String responseBody) {
        try {
            return objectMapper.readTree(responseBody);
        } catch (Exception ex) {
            throw new EmbeddingClientException("EMBEDDING_RESPONSE_PARSE_ERROR", "Failed to parse embedding response body", ex);
        }
    }

    /**
     * 这里同时做两层校验：
     * 1. 返回 embedding 数量必须与输入数量一致；
     * 2. 每个 embedding 的维度必须与配置一致。
     *
     * 否则后续写 Milvus 时就会出现“provider 维度”和“collection 维度”错位的问题。
     */
    private List<float[]> extractEmbeddings(JsonNode root, int expectedCount) {
        JsonNode dataNode = root.path("data");
        if (!dataNode.isArray()) {
            throw new EmbeddingClientException("EMBEDDING_RESPONSE_PARSE_ERROR", "Embedding response missing data array");
        }

        float[][] orderedEmbeddings = new float[expectedCount][];
        int sequentialIndex = 0;
        for (JsonNode item : dataNode) {
            int index = item.path("index").isInt() ? item.path("index").asInt() : sequentialIndex;
            if (index < 0 || index >= expectedCount) {
                throw new EmbeddingClientException("EMBEDDING_RESPONSE_PARSE_ERROR", "Embedding response contains invalid index");
            }
            orderedEmbeddings[index] = parseSingleEmbedding(item.path("embedding"));
            sequentialIndex++;
        }

        List<float[]> result = new ArrayList<>(expectedCount);
        for (float[] embedding : orderedEmbeddings) {
            if (embedding == null) {
                throw new EmbeddingClientException("EMBEDDING_RESPONSE_PARSE_ERROR", "Embedding response count does not match input count");
            }
            result.add(embedding);
        }
        return result;
    }

    private float[] parseSingleEmbedding(JsonNode embeddingNode) {
        if (!embeddingNode.isArray()) {
            throw new EmbeddingClientException("EMBEDDING_RESPONSE_PARSE_ERROR", "Embedding item is not an array");
        }
        if (embeddingNode.size() != embeddingDimension) {
            throw new EmbeddingClientException(
                    "EMBEDDING_DIMENSION_MISMATCH",
                    "Embedding dimension mismatch, expected=" + embeddingDimension + ", actual=" + embeddingNode.size()
            );
        }

        float[] vector = new float[embeddingNode.size()];
        for (int i = 0; i < embeddingNode.size(); i++) {
            JsonNode numberNode = embeddingNode.get(i);
            if (numberNode == null || !numberNode.isNumber()) {
                throw new EmbeddingClientException("EMBEDDING_RESPONSE_PARSE_ERROR", "Embedding item contains non-numeric value");
            }
            vector[i] = numberNode.floatValue();
        }
        return vector;
    }

    private Integer extractUsageValue(JsonNode root, String fieldName) {
        JsonNode usageNode = root.path("usage").path(fieldName);
        if (usageNode.isInt() || usageNode.isLong()) {
            return usageNode.asInt();
        }
        return null;
    }

    private EmbeddingClientException buildHttpError(int statusCode, String responseBody) {
        String providerMessage = extractProviderErrorMessage(responseBody);
        String message = "Embedding HTTP error, status=" + statusCode;
        if (StringUtils.hasText(providerMessage)) {
            message = message + ", reason=" + providerMessage;
        }
        return new EmbeddingClientException("EMBEDDING_HTTP_ERROR", message);
    }

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

    private String trimError(String error) {
        if (!StringUtils.hasText(error)) {
            return "unknown";
        }
        String trimmed = error.trim().replaceAll("\\s+", " ");
        return trimmed.length() > 300 ? trimmed.substring(0, 300) : trimmed;
    }

    /**
     * 出网策略检查（EMBEDDING 路径）。egressPolicy 为 null 时跳过（兼容测试）。
     */
    private void checkEgressEmbedding(URI endpoint) {
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
            egressPolicy.checkAndLog(host, port, EgressLogEntity.PURPOSE_EMBEDDING,
                    StringUtils.hasText(defaultModelName) ? defaultModelName.trim() : null);
        } catch (com.repolens.common.exception.BizException blocked) {
            throw blocked;
        } catch (Exception unexpected) {
            log.warn("EgressPolicy check failed in EmbeddingService (fail-safe), err={}", unexpected.getMessage());
        }
    }
}
