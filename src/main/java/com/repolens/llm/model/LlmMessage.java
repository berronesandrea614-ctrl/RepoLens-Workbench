package com.repolens.llm.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * agentic 多轮对话里的一条消息，对应 OpenAI-Compatible 的 messages[]。
 *
 * 四种角色：
 * - system / user：常规提示词；
 * - assistant：模型回复；当模型要求调用工具时，content 可空、toolCalls 非空；
 * - tool：工具执行结果回填，必须带 toolCallId 关联到对应的 assistant.toolCalls[i].id。
 */
@Data
@Builder
public class LlmMessage {

    /** system | user | assistant | tool */
    private String role;

    /** 文本内容。assistant 发起工具调用这一轮可为空。 */
    private String content;

    /** 仅 role=assistant：模型本轮要求调用的工具。 */
    private List<ToolCall> toolCalls;

    /** 仅 role=tool：本条结果对应的 tool_call id。 */
    private String toolCallId;
}
