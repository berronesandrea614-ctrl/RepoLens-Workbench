package com.repolens.llm.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * LLM 调用返回对象。
 * 包含文本结果、粗略 token 统计和错误字段，便于写入 llm_call_log。
 * agentic 场景下额外携带 toolCalls：非空表示模型要求先调工具再继续，而非给出最终答案。
 */
@Data
@Builder
public class LlmResponse {

    private String content;
    private String modelName;
    private Integer promptTokens;
    private Integer completionTokens;
    private Long costMs;
    private Boolean success;
    private String errorCode;
    private String errorMessage;

    /**
     * 模型本轮要求调用的工具列表。
     * 非空 = agent 还要继续（执行工具、回填结果、再问模型）；
     * 空 = 模型给出最终答案（content）。单轮问答恒为空。
     */
    private List<ToolCall> toolCalls;

    /** OpenAI-Compatible 的 finish_reason，如 stop / tool_calls / length。可空。 */
    private String finishReason;
}
