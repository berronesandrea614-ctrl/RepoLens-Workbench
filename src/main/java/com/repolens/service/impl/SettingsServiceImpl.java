package com.repolens.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.repolens.common.util.SsrfGuard;
import com.repolens.domain.dto.LlmSettingsUpdateRequest;
import com.repolens.domain.vo.EmbeddingSettingsVO;
import com.repolens.domain.vo.LlmSettingsVO;
import com.repolens.domain.vo.LlmTestResultVO;
import com.repolens.llm.config.LlmRuntimeConfig;
import com.repolens.service.SettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/**
 * 运行时设置服务实现。
 *
 * <p>安全边界：读接口只回传脱敏 api-key；连接测试构造【一次性】请求，不落库、不改运行时配置，
 * 且对任何错误信息都把出现的 api-key 脱敏后再返回，绝不外泄完整 key。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettingsServiceImpl implements SettingsService {

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final int TEST_TIMEOUT_MS = 10_000;
    /** 连接测试统一失败文案：不回显上游精确状态码/错误体，避免被当探针扫内网端口。 */
    private static final String TEST_FAIL_MESSAGE = "连接失败或地址不被允许";
    private static final String TEST_SUCCESS_MESSAGE = "连接成功，模型可用";

    private final LlmRuntimeConfig llmRuntimeConfig;
    private final ObjectMapper objectMapper;

    /**
     * 连接测试专用单例 HttpClient，connectTimeout 与 TEST_TIMEOUT_MS 一致。
     * 测试连接是低频操作，但每次创建 HttpClient 仍会留下 selector 线程，单例规避该问题。
     */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(TEST_TIMEOUT_MS))
            .build();

    // embedding 配置是启动期静态配置（切换需重启 + 可能重建 Milvus collection），
    // 这里只做只读透出，不提供运行时修改入口。
    @Value("${repolens.embedding.provider:mock}")
    private String embeddingProvider;

    @Value("${repolens.embedding.model-name:mock-embedding}")
    private String embeddingModelName;

    @Value("${repolens.embedding.dimension:384}")
    private int embeddingDimension;

    @Override
    public LlmSettingsVO getLlm() {
        return currentView();
    }

    @Override
    public EmbeddingSettingsVO getEmbedding() {
        String normalized = StringUtils.hasText(embeddingProvider)
                ? embeddingProvider.trim().toLowerCase(Locale.ROOT)
                : "mock";
        return EmbeddingSettingsVO.builder()
                .provider(normalized)
                .modelName(embeddingModelName)
                .dimension(embeddingDimension)
                .mock("mock".equals(normalized))
                .build();
    }

    @Override
    public LlmSettingsVO updateLlm(LlmSettingsUpdateRequest request) {
        if (request != null) {
            // 保存前也做 SSRF 校验：拒绝把 base-url 存成回环/私网地址，
            // 除非 provider 明确是本地类（ollama/local）——那种场景 localhost 本就是意图。
            String normalizedProvider = normalizeProvider(request.getProvider());
            assertBaseUrlAllowed(request.getBaseUrl(), normalizedProvider);
            llmRuntimeConfig.update(
                    request.getProvider(),
                    request.getBaseUrl(),
                    request.getApiKey(),
                    request.getModelName(),
                    request.getTimeoutMs());
        }
        return currentView();
    }

    @Override
    public LlmTestResultVO testLlm(LlmSettingsUpdateRequest request) {
        // 测试连接时 API Key 留空 → 回落到已保存的密钥（与 base-url/model 的留空回落一致），
        // 否则用户在设置页点“测试连接”而没重新输入密钥时，会用空密钥外呼导致 401 假失败。
        String apiKey = request == null || !StringUtils.hasText(request.getApiKey())
                ? llmRuntimeConfig.getApiKey()
                : request.getApiKey();
        try {
            String normalizedProvider = normalizeProvider(request == null ? null : request.getProvider());
            // mock provider 无需外呼，直接成功。
            if ("mock".equals(normalizedProvider)) {
                return LlmTestResultVO.builder().ok(true).message("mock provider，无需外部连接").build();
            }

            String baseUrl = request.getBaseUrl();
            if (!StringUtils.hasText(baseUrl)) {
                return LlmTestResultVO.builder().ok(false).message("base-url 未配置").build();
            }
            String modelName = StringUtils.hasText(request.getModelName())
                    ? request.getModelName().trim()
                    : llmRuntimeConfig.getModelName();
            if (!StringUtils.hasText(modelName)) {
                return LlmTestResultVO.builder().ok(false).message("model-name 未配置").build();
            }

            // SSRF 防护：把 base-url 的 host 解析成真实 IP 再校验，拒绝回环/私网，
            // 挡住“连接测试”被拿来扫内网端口（如 http://127.0.0.1:9091）。
            // ollama/local 明确指向本机，只放行回环。
            try {
                SsrfGuard.assertHostAllowed(extractHost(baseUrl), isLocalProvider(normalizedProvider));
            } catch (RuntimeException ssrf) {
                return LlmTestResultVO.builder().ok(false).message(TEST_FAIL_MESSAGE).build();
            }

            return doTestCall(baseUrl, apiKey, modelName);
        } catch (Exception ex) {
            // 完全失败安全：任何异常都转成通用 ok=false，不回显内部错误。
            return LlmTestResultVO.builder().ok(false).message(TEST_FAIL_MESSAGE).build();
        }
    }

    /**
     * 发一次性 /chat/completions 探测，2xx 且含 choices[0].message 即视为可用。
     * 无论上游返回什么状态码/错误体，对外都只给通用文案，不回显精确状态，避免信息泄露。
     */
    private LlmTestResultVO doTestCall(String baseUrl, String apiKey, String modelName) {
        try {
            URI endpoint = resolveEndpoint(baseUrl);

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(endpoint)
                    .timeout(Duration.ofMillis(TEST_TIMEOUT_MS))
                    .header("Content-Type", "application/json");
            if (StringUtils.hasText(apiKey)) {
                builder.header("Authorization", "Bearer " + apiKey.trim());
            }
            HttpRequest httpRequest = builder
                    .POST(HttpRequest.BodyPublishers.ofString(buildProbeBody(modelName)))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                JsonNode root = safeReadTree(response.body());
                if (root != null && root.path("choices").path(0).path("message").isObject()) {
                    return LlmTestResultVO.builder().ok(true).message(TEST_SUCCESS_MESSAGE).build();
                }
            }
            // 非 2xx / 响应缺字段：统一通用失败，不回显 HTTP 状态码与上游错误体。
            return LlmTestResultVO.builder().ok(false).message(TEST_FAIL_MESSAGE).build();
        } catch (Exception ex) {
            return LlmTestResultVO.builder().ok(false).message(TEST_FAIL_MESSAGE).build();
        }
    }

    private String normalizeProvider(String provider) {
        return StringUtils.hasText(provider) ? provider.trim().toLowerCase(Locale.ROOT) : "mock";
    }

    /** ollama/local 视为本地 provider，允许 base-url 指向回环（localhost）。 */
    private boolean isLocalProvider(String normalizedProvider) {
        return "ollama".equals(normalizedProvider) || "local".equals(normalizedProvider);
    }

    /** 从 base-url 中解析 host（供 SSRF 校验用）。 */
    private String extractHost(String baseUrl) {
        try {
            return URI.create(baseUrl.trim()).getHost();
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 保存 base-url 前的 SSRF 校验。空 base-url（如切回 mock）跳过；
     * 非本地 provider 拒绝回环/私网地址，本地 provider 放行回环。
     */
    private void assertBaseUrlAllowed(String baseUrl, String normalizedProvider) {
        if (!StringUtils.hasText(baseUrl)) {
            return;
        }
        SsrfGuard.assertHostAllowed(extractHost(baseUrl), isLocalProvider(normalizedProvider));
    }

    private String buildProbeBody(String modelName) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", modelName);
        root.put("stream", false);
        root.put("temperature", 0d);
        root.put("max_tokens", 1);
        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "user").put("content", "ping");
        return root.toString();
    }

    /** base-url 只配置到根路径时自动补 /chat/completions（与 OpenAiCompatibleLlmClient 一致）。 */
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

    private JsonNode safeReadTree(String body) {
        if (!StringUtils.hasText(body)) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception ex) {
            return null;
        }
    }

    /** 从运行时配置构建脱敏视图。 */
    private LlmSettingsVO currentView() {
        return LlmSettingsVO.builder()
                .provider(llmRuntimeConfig.getProvider())
                .baseUrl(llmRuntimeConfig.getBaseUrl())
                .modelName(llmRuntimeConfig.getModelName())
                .timeoutMs(llmRuntimeConfig.getTimeoutMs())
                .apiKeyMasked(maskApiKey(llmRuntimeConfig.getApiKey()))
                .build();
    }

    /** 脱敏 api-key：空 -> ""；否则 "****" + 末 4 位（不足 4 位则用全部）。 */
    public static String maskApiKey(String key) {
        if (!StringUtils.hasText(key)) {
            return "";
        }
        String trimmed = key.trim();
        String last4 = trimmed.length() <= 4 ? trimmed : trimmed.substring(trimmed.length() - 4);
        return "****" + last4;
    }

}
