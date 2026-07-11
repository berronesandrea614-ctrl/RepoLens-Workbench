package com.repolens.llm.impl;

import com.repolens.llm.LlmClient;
import com.repolens.llm.LlmClientException;
import com.repolens.llm.config.LlmRuntimeConfig;
import com.repolens.llm.model.LlmRequest;
import com.repolens.llm.model.LlmResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * LLM provider 路由器。
 * 路由规则：
 * 1. provider=mock 时走 MockLlmClient，保证本地无 key 也可完整演示；
 * 2. 所有 OpenAI Chat Completions 兼容协议的 provider 统一走 OpenAiCompatibleLlmClient：
 *    openai-compatible / deepseek-compatible / openai / deepseek / anthropic / gemini /
 *    qwen / zhipu / moonshot / kimi 等主流预设（它们仅 base-url + model 不同，协议一致）。
 *    注意：anthropic 走的是"OpenAI 兼容网关"的 base-url，本客户端不发 Anthropic 原生协议。
 * 3. provider 比较大小写不敏感；
 * 4. provider 未识别时抛出清晰异常，由上层触发降级，而不是静默改走 mock。
 * 5. provider=ollama/local 复用 OpenAiCompatibleLlmClient（本地 Ollama 的 OpenAI 兼容端点），api-key 可选。
 */
@Primary
@Component
@RequiredArgsConstructor
public class RoutingLlmClient implements LlmClient {

    private final MockLlmClient mockLlmClient;
    private final OpenAiCompatibleLlmClient openAiCompatibleLlmClient;
    private final LlmRuntimeConfig llmRuntimeConfig;

    @Override
    public LlmResponse generate(LlmRequest request) {
        return route().generate(request);
    }

    /**
     * 必须转发流式到真实 client：否则用接口默认 generateStream（generate 一次 + 整段一个 token），
     * 逐 token 流式会被静默绕过——这正是"agentMode=null 却只有 1 个 token 事件"的根因。
     */
    @Override
    public void generateStream(LlmRequest request, Consumer<String> onToken, Consumer<LlmResponse> onDone) {
        route().generateStream(request, onToken, onDone);
    }

    @Override
    public void generateStreamWithTools(LlmRequest request, com.repolens.llm.StreamWithToolsListener listener) {
        route().generateStreamWithTools(request, listener);
    }

    private LlmClient route() {
        String provider = llmRuntimeConfig.getProvider();
        String selected = StringUtils.hasText(provider) ? provider.trim().toLowerCase(Locale.ROOT) : "mock";
        return switch (selected) {
            case "mock" -> mockLlmClient;
            // 所有 OpenAI Chat Completions 兼容协议的 provider 统一走同一个客户端，
            // 差异只在 base-url / model（由 LlmRuntimeConfig 提供），协议一致。
            case "openai-compatible", "deepseek-compatible", "ollama", "local",
                 "openai", "deepseek", "anthropic", "gemini",
                 "qwen", "zhipu", "moonshot", "kimi" -> openAiCompatibleLlmClient;
            default -> throw new LlmClientException("LLM_CONFIG_MISSING", "Unsupported LLM provider: " + selected);
        };
    }
}
