package com.repolens.kernel.mcp;

import java.util.List;
import java.util.Map;

/**
 * MCP client（M7.4，最小真实骨架）：消费「外部」工具的抽象。真实 MCP 会连 stdio/SSE 服务器发现工具，
 * 本里程碑<b>只做进程内 loopback</b>——无任何出网点（红线：无新出网点），验证「工具发现 → 调用」的接线成立，
 * 真实传输层诚实标注为后续。
 *
 * @see LoopbackMcpClientService 进程内回环实现（发现一个 echo stub 工具并可真调用）
 */
public interface McpClientService {

    /** 一个被发现的 MCP 工具描述。 */
    record McpToolInfo(String name, String description) {
    }

    /** 发现所有可用的（外部）MCP 工具。 */
    List<McpToolInfo> discoverTools();

    /**
     * 调用一个已发现的 MCP 工具。
     *
     * @param toolName 工具名
     * @param args     实参
     * @return 工具返回文本；未知工具返回错误串（fail-safe）
     */
    String callTool(String toolName, Map<String, Object> args);
}
