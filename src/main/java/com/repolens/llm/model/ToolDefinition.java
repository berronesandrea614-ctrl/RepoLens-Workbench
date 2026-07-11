package com.repolens.llm.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Agentic 检索阶段的工具定义。
 * 对应 OpenAI-Compatible function-calling 协议里的 tools[].function：
 * name + description + JSON Schema parameters。
 *
 * description 必须写精准，否则 LLM 容易选错工具。
 * parameters 用最简 JSON Schema（type=object + properties + required），
 * 由 CodeAnswerService 构造、由 LlmClient 序列化进请求体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolDefinition {

    /** 工具名，与 ToolInvokeService 分发用的 toolName 一致，如 searchCodeChunks。 */
    private String name;

    /** 工具用途描述，给 LLM 看，决定它何时选这个工具。 */
    private String description;

    /**
     * 参数 JSON Schema，形如：
     * { "type":"object", "properties": { "query": {"type":"string","description":"..."} }, "required":["query"] }
     */
    private Map<String, Object> parameters;
}
