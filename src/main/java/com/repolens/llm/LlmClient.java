package com.repolens.llm;

import com.repolens.llm.model.LlmRequest;
import com.repolens.llm.model.LlmResponse;

/**
 * 第八阶段 LLM 调用抽象。
 * 通过统一接口隔离 provider 细节，便于 mock 与真实客户端切换。
 */
public interface LlmClient {

    /**
     * 执行一次单轮生成调用。
     *
     * @param request 生成请求
     * @return 生成结果；调用失败时应抛异常或返回 success=false
     */
    LlmResponse generate(LlmRequest request);

    /**
     * 流式生成：逐 token 回调 onToken，结束时回调一次 onDone 携带完整 LlmResponse。
     *
     * 默认实现「一次性」：直接调用 {@link #generate(LlmRequest)}，把整段 content 作为
     * 单个 chunk 推给 onToken，再回调 onDone。这样任何 client 都天然支持流式契约，
     * 只有真正能逐 token 拉取的 {@code OpenAiCompatibleLlmClient} 才需要覆写为真流式。
     *
     * 约定：实现不应把流式过程中的中间错误直接抛出，而应尽力降级
     * （如回落到 {@link #generate(LlmRequest)} 再一次性推送）。仅当彻底失败时才抛异常，
     * 由上层做证据摘要降级。
     *
     * @param request 生成请求
     * @param onToken 每个文本增量回调（可能被调用多次）
     * @param onDone  结束回调，携带聚合后的完整 LlmResponse（恰调用一次）
     */
    default void generateStream(LlmRequest request,
                                java.util.function.Consumer<String> onToken,
                                java.util.function.Consumer<LlmResponse> onDone) {
        LlmResponse response = generate(request);
        if (response != null && response.getContent() != null && !response.getContent().isEmpty()) {
            onToken.accept(response.getContent());
        }
        onDone.accept(response);
    }

    /**
     * 带工具的流式生成：解析 delta.tool_calls 与 delta.content，流结束后组装完整 LlmResponse。
     *
     * 默认实现「一次性」：调用 {@link #generate(LlmRequest)}，把每个 tool_call 名称单独通知
     * {@code listener.onToolCallStart}，再把 content 作为单个 token 推送，最终调用
     * {@code listener.onDone}。
     * 真正支持 stream+tools 的实现（{@link com.repolens.llm.impl.OpenAiCompatibleLlmClient}）
     * 需覆写此方法。
     *
     * 约定：实现应尽力降级（如回落到 {@link #generate}），不直接抛异常。
     *
     * @param request  生成请求（通常含 tools）
     * @param listener 事件回调
     */
    default void generateStreamWithTools(LlmRequest request, StreamWithToolsListener listener) {
        LlmResponse response = generate(request);
        if (response != null) {
            if (response.getToolCalls() != null) {
                for (com.repolens.llm.model.ToolCall tc : response.getToolCalls()) {
                    if (tc.getName() != null && !tc.getName().isEmpty()) {
                        listener.onToolCallStart(tc.getName());
                    }
                }
            }
            if (response.getContent() != null && !response.getContent().isEmpty()) {
                listener.onContentToken(response.getContent());
            }
        }
        listener.onDone(response);
    }
}
