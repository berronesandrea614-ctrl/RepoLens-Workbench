package com.repolens.service.support;

import java.util.List;
import java.util.Map;

/**
 * MCP (Model Context Protocol) 客户端服务。
 * 渐进式发现：先 list tools 摘要，按需 load 完整 schema，避免全量注入撑爆 context。
 */
public interface McpClientService {

    /** 工具摘要（名 + 描述），供 LLM 按需选择。 */
    record McpToolSummary(String name, String description) {}

    /** 工具完整定义（含 inputSchema），供 AgentToolCatalog 动态注入。 */
    record McpToolDefinition(String name, String description, Map<String, Object> inputSchema) {}

    /**
     * 发现 MCP 工具：返回匹配 query 的工具名 + 摘要。
     * 实现应查询 mcp_server 表并调用 tools/list。
     */
    List<McpToolSummary> discoverTools(String query);

    /**
     * 加载某个 MCP 工具的完整 schema。
     * 用于 LLM 选定工具后动态注入 function definition。
     */
    McpToolDefinition loadTool(String name);

    /**
     * 调用 MCP 工具，返回结果。
     * 执行前需通过权限引擎（默认 ASK）+ SSRF 防护。
     */
    Map<String, Object> invoke(String toolName, Map<String, Object> args);
}
