package com.repolens.llm.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * LLM 生成请求对象。
 * 单轮问答只需 systemPrompt + userPrompt；
 * agentic 多步检索时额外携带 tools（可调用工具列表）和 messages（多轮对话历史）。
 */
@Data
@Builder
public class LlmRequest {

    private String modelName;
    private String systemPrompt;
    private String userPrompt;
    private Double temperature;
    private Integer timeoutMs;

    /**
     * 可调用工具列表。为空（单轮问答）时按原逻辑只发 system+user。
     * 非空时按 OpenAI-Compatible function-calling 协议发 tools，LLM 可返回 tool_calls。
     */
    private List<ToolDefinition> tools;

    /**
     * 多轮对话历史。agentic loop 里把 assistant 的 tool_calls 和工具执行结果（role=tool）
     * 逐轮追加进来再回传，让 LLM 在已有观察上继续决策。
     * 为空时回退到 systemPrompt + userPrompt 的单轮形态。
     */
    private List<LlmMessage> messages;
}
