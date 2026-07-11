package com.repolens.llm;

import com.repolens.llm.model.LlmResponse;

/**
 * 带工具的流式调用监听器。
 * 用于 {@link LlmClient#generateStreamWithTools} 的事件回调，支持逐 token 推送与工具调用通知。
 */
public interface StreamWithToolsListener {

    /**
     * 每个内容 delta 文本回调（可能是思考文本或最终答案片段，调用方自行判断是否下发 SSE）。
     * 每次回调时 token 一定非 null 非空。
     */
    void onContentToken(String token);

    /**
     * 工具调用名称已知时回调（流式 tool_calls[i].function.name 到达时）。
     * 用于尽早通知调用方"模型要调哪个工具"（可提前发 SSE step pending 态）。
     * name 一定非 null 非空。
     */
    void onToolCallStart(String toolName);

    /**
     * 流结束：携带完整组装后的 LlmResponse（含所有 toolCalls + 累计 content）。
     * 恰好调用一次。response 可能为 null（极端失败场景，实现应尽量避免）。
     */
    void onDone(LlmResponse response);
}
