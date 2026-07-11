package com.repolens.service.impl;

import com.repolens.service.EmbeddingClientException;
import com.repolens.service.EmbeddingService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Embedding Provider 路由器。
 * 路由规则：
 * 1. provider=mock 时走 MockEmbeddingService，保证本地无外部 key 也能完整演示；
 * 2. provider=openai-compatible 时走 OpenAiCompatibleEmbeddingService；
 * 3. provider 比较大小写不敏感；
 * 4. provider 未识别时抛清晰异常，而不是静默回退到 mock。
 *
 * 这里使用 @Primary，是为了让系统中所有注入 EmbeddingService 的地方，
 * 统一拿到“可路由”的抽象，而不是某个具体 provider 实现。
 *
 * Fail-safe 降级（仅对真实 provider 生效）：
 * 当 provider=openai-compatible 但真实调用抛异常（endpoint 未启动 / 配置错误 / 超时等），
 * 不让异常冒泡打断 RAG/检索链路，而是记一条 WARN 后回退到 MockEmbeddingService。
 * 这样语义检索至少还能返回（关键词回退 + mock 向量），而不是 500。
 *
 * 维度安全：MockEmbeddingService 与 OpenAiCompatibleEmbeddingService 都以
 * repolens.embedding.dimension 为准，因此回退产生的 mock 向量维度与 Milvus 中
 * 既有向量维度一致，混用回退不会破坏 collection 维度。
 * 若真实 provider 返回维度与配置不符，那是 OpenAiCompatibleEmbeddingService 已经
 * 校验并抛出的配置错误，回退路径依然使用同一份配置维度，保持一致。
 */
@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class RoutingEmbeddingService implements EmbeddingService {

    private static final String MOCK = "mock";
    private static final String OPENAI_COMPATIBLE = "openai-compatible";

    private final MockEmbeddingService mockEmbeddingService;
    private final OpenAiCompatibleEmbeddingService openAiCompatibleEmbeddingService;

    @Value("${repolens.embedding.provider:mock}")
    private String provider;

    /**
     * 启动时把 mock embedding 的真实含义讲清楚，避免“检索能跑但语义无效”被误当成正常。
     * 只在启动打一次 WARN；provider 拼写错误留给 resolveProvider 在调用时抛清晰异常。
     */
    @PostConstruct
    void warnIfMockProvider() {
        String selected = StringUtils.hasText(provider) ? provider.trim().toLowerCase(Locale.ROOT) : MOCK;
        if (MOCK.equals(selected)) {
            log.warn("embedding=mock: 向量检索为伪随机，仅关键词召回有效；" +
                    "生产/演示请配置本地 Ollama bge-m3/nomic-embed-text（见 README 与 application.yml repolens.embedding 注释）");
        }
    }

    @Override
    public float[] embed(String text) {
        String selected = resolveProvider();
        if (MOCK.equals(selected)) {
            return mockEmbeddingService.embed(text);
        }
        return withFallback(
                () -> openAiCompatibleEmbeddingService.embed(text),
                () -> mockEmbeddingService.embed(text)
        );
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        String selected = resolveProvider();
        if (MOCK.equals(selected)) {
            return mockEmbeddingService.embedBatch(texts);
        }
        return withFallback(
                () -> openAiCompatibleEmbeddingService.embedBatch(texts),
                () -> mockEmbeddingService.embedBatch(texts)
        );
    }

    /**
     * 解析并校验 provider。mock 与 openai-compatible 之外的取值属于配置错误，
     * 直接抛异常（而不是静默回退），避免把拼写错误的 provider 悄悄当成 mock。
     */
    private String resolveProvider() {
        String selected = StringUtils.hasText(provider) ? provider.trim().toLowerCase(Locale.ROOT) : MOCK;
        if (!MOCK.equals(selected) && !OPENAI_COMPATIBLE.equals(selected)) {
            throw new EmbeddingClientException(
                    "EMBEDDING_CONFIG_MISSING",
                    "Unsupported embedding provider: " + selected
            );
        }
        return selected;
    }

    /**
     * 真实 provider 调用失败时回退到 mock：只吞掉真实调用的运行时异常，
     * 保持 RAG/检索链路可用。回退本身若再失败则照常抛出（说明连本地 mock 都不可用，
     * 属于真正需要暴露的错误）。
     */
    private <T> T withFallback(Supplier<T> realCall, Supplier<T> mockCall) {
        try {
            return realCall.get();
        } catch (RuntimeException ex) {
            String reason = ex instanceof EmbeddingClientException ece
                    ? ece.getErrorCode() + ": " + ece.getMessage()
                    : ex.getMessage();
            log.warn("Real embedding provider '{}' failed, falling back to mock embedding. reason={}",
                    OPENAI_COMPATIBLE, reason);
            return mockCall.get();
        }
    }
}
