package com.repolens.llm.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * LLM 在一轮决策里要求调用的工具。
 * 对应 OpenAI-Compatible 响应里的 choices[0].message.tool_calls[]。
 *
 * id 用于在把工具执行结果回填给模型时关联（role=tool 的 tool_call_id）。
 * arguments 是 LLM 给出的参数（已从 JSON 字符串解析成 Map），
 * 由 ToolInvokeService 校验后执行——绝不信任 LLM 直接执行写操作。
 */
@Data
@Builder
public class ToolCall {

    /** provider 返回的 tool_call id，用于回填结果时关联。 */
    private String id;

    /** 工具名，对应 ToolDefinition.name / ToolInvokeService 的 toolName。 */
    private String name;

    /** LLM 给出的调用参数（已解析）。 */
    private Map<String, Object> arguments;
}
