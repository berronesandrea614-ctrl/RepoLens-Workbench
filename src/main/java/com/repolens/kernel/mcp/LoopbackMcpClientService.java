package com.repolens.kernel.mcp;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 进程内回环 MCP client（M7.4 最小真实实现）。<b>不出网</b>：把「外部 MCP 服务器」用一个进程内 stub 顶替，
 * 发现一个 {@code mcp__loopback__echo} 工具并可真调用（原样回显参数）。
 *
 * <p>目的：证明「MCP 工具发现 → 调用」的接线在内核里跑得通，而非空壳——{@link #discoverTools()} 真返回工具、
 * {@link #callTool} 真执行回显。真实的 stdio/SSE 传输层与外部服务器接入诚实顺延到后续里程碑
 * （见类注释与报告说明）。命名遵循 MCP 惯例 {@code mcp__<server>__<tool>}。
 */
@Component
public class LoopbackMcpClientService implements McpClientService {

    /** loopback 服务器暴露的唯一 stub 工具名（MCP 命名惯例 mcp__server__tool）。 */
    public static final String ECHO_TOOL = "mcp__loopback__echo";

    @Override
    public List<McpToolInfo> discoverTools() {
        return List.of(new McpToolInfo(ECHO_TOOL,
                "回环 MCP stub：原样回显传入参数（用于验证 MCP 工具发现→调用接线，非出网工具）。"));
    }

    @Override
    public String callTool(String toolName, Map<String, Object> args) {
        if (!ECHO_TOOL.equals(toolName)) {
            return "未知 MCP 工具：" + toolName + "。可用：" + ECHO_TOOL;
        }
        return "[mcp:loopback:echo] " + (args == null ? "{}" : args.toString());
    }
}
