package com.repolens.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.domain.vo.SymbolVO;
import com.repolens.service.CodeGraphService;
import com.repolens.service.RagRetrievalService;
import com.repolens.service.RequirementInsightService;
import com.repolens.service.SymbolQueryService;
import com.repolens.tool.ReadonlyToolService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP JSON-RPC 2.0 server endpoint (Streamable HTTP / non-streaming variant).
 *
 * <p><b>Transport:</b> HTTP POST /mcp — spec-compatible with
 * {@code claude mcp add repolens --transport http --url http://127.0.0.1:8080/mcp
 *   --header "X-RepoLens-Token: <token>"}.
 *
 * <p><b>Security gates (both enforced before any processing):</b>
 * <ol>
 *   <li>Loopback-only: remote address must be 127.0.0.1, ::1, or 0:0:0:0:0:0:0:1 → 403 if not.</li>
 *   <li>Token check: X-RepoLens-Token header must match the startup-generated token → 401 if not.</li>
 * </ol>
 *
 * <p><b>SDK choice — hand-rolled over official MCP Java SDK:</b>
 * {@code io.modelcontextprotocol.sdk:mcp} requires Spring AI (which brings reactor/webflux
 * transitive deps) and its servlet-SSE transport is only available in Spring AI 1.0+, which
 * conflicts with our Spring Boot 3.3.5 + Spring MVC (non-reactive) classpath.
 * A hand-rolled minimal JSON-RPC 2.0 @RestController is ~300 LOC, fully spec-compliant for
 * the initialize/tools/resources subset needed by Claude Code, and avoids all dependency risk.
 *
 * <p><b>Supported JSON-RPC methods:</b>
 * {@code initialize}, {@code tools/list}, {@code tools/call},
 * {@code resources/list}, {@code resources/read}.
 * Notifications (requests without {@code id}) return 202 Accepted with no body.
 *
 * <p><b>Tools exposed:</b>
 * Data tools (read-only, delegate to existing services):
 * {@code rag_search}, {@code call_graph}, {@code symbol_search},
 * {@code requirement_insight}, {@code read_file}.
 * UI-action tools (harmless, forwarded to frontend via SSE):
 * {@code open_file}, {@code focus_symbol}, {@code show_requirement_viz}.
 * No write-file MCP tools are exposed; file edits go through Claude's native Write.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class McpController {

    /** Fixed userId for MCP calls — MVP single-user app. */
    static final Long MCP_USER_ID = 1L;

    private final McpTokenHolder tokenHolder;
    private final McpContextHolder contextHolder;
    private final McpIdeStateHolder ideStateHolder;
    private final McpUiActionBroker uiActionBroker;
    private final RagRetrievalService ragRetrievalService;
    private final CodeGraphService codeGraphService;
    private final SymbolQueryService symbolQueryService;
    private final RequirementInsightService requirementInsightService;
    private final ReadonlyToolService readonlyToolService;
    private final ObjectMapper objectMapper;

    // ── Main entry point ─────────────────────────────────────────────────────

    @PostMapping(value = "/mcp", consumes = "application/json", produces = "application/json")
    public ResponseEntity<Object> handleMcp(HttpServletRequest request,
                                             @RequestBody Map<String, Object> body) {
        // Gate 1: Loopback check — non-loopback → 403
        if (!isLoopback(request)) {
            log.warn("[MCP] Rejected non-loopback request from: {}", request.getRemoteAddr());
            return ResponseEntity.status(403)
                    .body(Map.of("error", "Forbidden: /mcp is restricted to loopback (127.0.0.1)"));
        }

        // Gate 2: Token check — missing/wrong token → 401
        String providedToken = request.getHeader("X-RepoLens-Token");
        if (!tokenHolder.matches(providedToken)) {
            log.warn("[MCP] Rejected request with invalid or missing X-RepoLens-Token");
            return ResponseEntity.status(401)
                    .body(Map.of("error", "Unauthorized: invalid or missing X-RepoLens-Token"));
        }

        // Validate JSON-RPC 2.0 envelope
        if (!"2.0".equals(body.get("jsonrpc"))) {
            return ResponseEntity.ok(rpcError(null, -32600, "Invalid Request: jsonrpc must be \"2.0\""));
        }

        Object rawMethod = body.get("method");
        if (rawMethod == null) {
            return ResponseEntity.ok(rpcError(null, -32600, "Invalid Request: method is required"));
        }
        if (!(rawMethod instanceof String)) {
            return ResponseEntity.ok(rpcError(null, -32600, "Invalid Request: method must be a string"));
        }
        String method = (String) rawMethod;

        // Notification (no "id" key) — return 202 Accepted, no body
        if (!body.containsKey("id")) {
            log.debug("[MCP] Notification: {}", method);
            return ResponseEntity.status(202).build();
        }

        Object id = body.get("id");

        @SuppressWarnings("unchecked")
        Map<String, Object> params = (body.get("params") instanceof Map<?, ?> m)
                ? (Map<String, Object>) m : Map.of();

        try {
            return switch (method) {
                case "initialize"       -> ResponseEntity.ok(handleInitialize(id, params));
                case "tools/list"       -> ResponseEntity.ok(handleToolsList(id));
                case "tools/call"       -> ResponseEntity.ok(handleToolsCall(id, params));
                case "resources/list"   -> ResponseEntity.ok(handleResourcesList(id));
                case "resources/read"   -> ResponseEntity.ok(handleResourcesRead(id, params));
                default -> ResponseEntity.ok(rpcError(id, -32601, "Method not found: " + method));
            };
        } catch (Exception e) {
            log.error("[MCP] Unexpected error for method {}: {}", method, e.getMessage(), e);
            return ResponseEntity.ok(rpcError(id, -32603, "Internal error: " + e.getMessage()));
        }
    }

    // ── Method handlers ───────────────────────────────────────────────────────

    private Map<String, Object> handleInitialize(Object id, Map<String, Object> params) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", "2024-11-05");
        result.put("capabilities", Map.of("tools", Map.of(), "resources", Map.of()));
        result.put("serverInfo", Map.of("name", "repolens", "version", "1.0.0"));
        return rpcResult(id, result);
    }

    private Map<String, Object> handleToolsList(Object id) {
        List<Map<String, Object>> tools = new ArrayList<>();

        // ── Data tools ──
        tools.add(buildTool("rag_search",
                "Search the codebase using RAG. Returns code snippets with file paths and line numbers.",
                schemaOf(List.of("query"),
                        prop("query",   "string",  "The search query"),
                        prop("repoId",  "integer", "Repository ID (defaults to current context repo)"),
                        prop("topK",    "integer", "Max results (default 5)"))));

        tools.add(buildTool("call_graph",
                "Build a call graph (callers + callees) for a symbol. Returns a graph VO.",
                schemaOf(List.of("symbolName"),
                        prop("symbolName", "string",  "Class or method name to look up"),
                        prop("repoId",     "integer", "Repository ID (defaults to current context repo)"),
                        prop("depth",      "integer", "Graph depth (default 2)"))));

        tools.add(buildTool("symbol_search",
                "Search for symbols (classes, methods) in the codebase by name.",
                schemaOf(List.of("symbolName"),
                        prop("symbolName", "string",  "Symbol name to search for"),
                        prop("repoId",     "integer", "Repository ID (defaults to current context repo)"))));

        tools.add(buildTool("requirement_insight",
                "Get the requirement intent visualization VO (steps, code changes, risk notes) for a requirement.",
                schemaOf(List.of("reqId"),
                        prop("reqId",  "integer", "Requirement ID"),
                        prop("repoId", "integer", "Repository ID (defaults to current context repo)"))));

        tools.add(buildTool("read_file",
                "Read file content from the repository. Path is validated against repo root (no traversal).",
                schemaOf(List.of("path"),
                        prop("path",      "string",  "Relative path within the repository"),
                        prop("repoId",    "integer", "Repository ID (defaults to current context repo)"),
                        prop("startLine", "integer", "Start line 1-based inclusive (optional)"),
                        prop("endLine",   "integer", "End line 1-based inclusive (optional)"))));

        // ── UI action tools ──
        tools.add(buildTool("open_file",
                "Request the editor to open a file and optionally scroll to a line. Returns immediately.",
                schemaOf(List.of("path"),
                        prop("path", "string",  "File path to open"),
                        prop("line", "integer", "Line number to scroll to (optional)"))));

        tools.add(buildTool("focus_symbol",
                "Request the editor to focus and highlight a symbol by its numeric ID.",
                schemaOf(List.of("symbolId"),
                        prop("symbolId", "integer", "Symbol ID to focus"))));

        tools.add(buildTool("show_requirement_viz",
                "Request the UI to open the requirement intent visualization card.",
                schemaOf(List.of("reqId"),
                        prop("reqId", "integer", "Requirement ID to visualize"))));

        return rpcResult(id, Map.of("tools", tools));
    }

    private Map<String, Object> handleToolsCall(Object id, Map<String, Object> params) {
        String toolName = (String) params.get("name");
        @SuppressWarnings("unchecked")
        Map<String, Object> args = (params.get("arguments") instanceof Map<?, ?> m)
                ? (Map<String, Object>) m : Map.of();
        try {
            String text = dispatchTool(toolName, args);
            return rpcResult(id, Map.of(
                    "content", List.of(Map.of("type", "text", "text", text)),
                    "isError", false));
        } catch (Exception e) {
            log.warn("[MCP] Tool {} error: {}", toolName, e.getMessage());
            return rpcResult(id, Map.of(
                    "content", List.of(Map.of("type", "text", "text", "Error: " + e.getMessage())),
                    "isError", true));
        }
    }

    private Map<String, Object> handleResourcesList(Object id) {
        List<Map<String, Object>> resources = List.of(
                Map.of("uri", "ide://active-file", "name", "Active File",
                        "description", "Currently active file path in the editor", "mimeType", "text/plain"),
                Map.of("uri", "ide://selection", "name", "Current Selection",
                        "description", "Currently selected code text in the editor", "mimeType", "text/plain")
        );
        return rpcResult(id, Map.of("resources", resources));
    }

    private Map<String, Object> handleResourcesRead(Object id, Map<String, Object> params) {
        String uri = (String) params.get("uri");
        if (uri == null) {
            return rpcError(id, -32602, "Invalid params: uri is required");
        }
        String text;
        if ("ide://active-file".equals(uri)) {
            McpIdeStateHolder.ActiveFileState af = ideStateHolder.getActiveFile();
            if (af != null) {
                // Return both path and content so Claude can use either.
                text = "File: " + af.filePath() + "\n\n" + af.content();
            } else {
                // Graceful degradation: fall back to contextHolder's bare path if set.
                String path = contextHolder.getActiveFilePath();
                text = (path != null)
                        ? "Active file: " + path + "\n(content not available — use POST /api/mcp/ide-state)"
                        : "(no active file set — use POST /api/mcp/ide-state)";
            }
        } else if ("ide://selection".equals(uri)) {
            McpIdeStateHolder.SelectionState sel = ideStateHolder.getSelection();
            if (sel != null) {
                StringBuilder sb = new StringBuilder();
                sb.append("File: ").append(sel.filePath());
                if (sel.startLine() != null && sel.endLine() != null) {
                    sb.append(" (lines ").append(sel.startLine()).append("-").append(sel.endLine()).append(")");
                }
                sb.append("\n\n").append(sel.text());
                text = sb.toString();
            } else {
                // Graceful degradation: fall back to contextHolder's bare selection text.
                String selText = contextHolder.getSelectionText();
                text = (selText != null)
                        ? selText
                        : "(no selection set — use POST /api/mcp/ide-state)";
            }
        } else {
            return rpcError(id, -32602, "Unknown resource URI: " + uri);
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("uri", uri);
        content.put("mimeType", "text/plain");
        content.put("text", text);
        return rpcResult(id, Map.of("contents", List.of(content)));
    }

    // ── Tool dispatch ─────────────────────────────────────────────────────────

    private String dispatchTool(String toolName, Map<String, Object> args) throws Exception {
        if (toolName == null) {
            throw new IllegalArgumentException("Tool name is required");
        }
        return switch (toolName) {
            case "rag_search"           -> doRagSearch(args);
            case "call_graph"           -> doCallGraph(args);
            case "symbol_search"        -> doSymbolSearch(args);
            case "requirement_insight"  -> doRequirementInsight(args);
            case "read_file"            -> doReadFile(args);
            case "open_file"            -> doOpenFile(args);
            case "focus_symbol"         -> doFocusSymbol(args);
            case "show_requirement_viz" -> doShowRequirementViz(args);
            default -> throw new IllegalArgumentException("Unknown tool: " + toolName);
        };
    }

    private String doRagSearch(Map<String, Object> args) throws Exception {
        String query  = requireStr(args, "query");
        Long repoId   = resolveRepoId(args);
        int  topK     = args.containsKey("topK") ? toInt(args.get("topK")) : 5;
        var  result   = ragRetrievalService.retrieve(repoId, MCP_USER_ID, query, topK);
        return objectMapper.writeValueAsString(result);
    }

    private String doCallGraph(Map<String, Object> args) throws Exception {
        String symbolName = requireStr(args, "symbolName");
        Long   repoId     = resolveRepoId(args);
        int    depth      = args.containsKey("depth") ? toInt(args.get("depth")) : 2;
        // Resolve symbol name → ID via SymbolQueryService (path safety not applicable here)
        List<SymbolVO> symbols = symbolQueryService.searchSymbols(MCP_USER_ID, repoId, symbolName);
        if (symbols == null || symbols.isEmpty()) {
            return "No symbol found with name: " + symbolName;
        }
        Long symbolId = symbols.get(0).getId();
        var  graph    = codeGraphService.buildGraph(MCP_USER_ID, repoId, symbolId, "both", depth, 0.0);
        return objectMapper.writeValueAsString(graph);
    }

    private String doSymbolSearch(Map<String, Object> args) throws Exception {
        String symbolName = requireStr(args, "symbolName");
        Long   repoId     = resolveRepoId(args);
        var    symbols    = symbolQueryService.searchSymbols(MCP_USER_ID, repoId, symbolName);
        return objectMapper.writeValueAsString(symbols);
    }

    private String doRequirementInsight(Map<String, Object> args) throws Exception {
        Long reqId  = requireLong(args, "reqId");
        Long repoId = resolveRepoId(args);
        var  vo     = requirementInsightService.insight(MCP_USER_ID, repoId, reqId);
        return objectMapper.writeValueAsString(vo);
    }

    private String doReadFile(Map<String, Object> args) {
        String  path      = requireStr(args, "path");
        Long    repoId    = resolveRepoId(args);
        Integer startLine = args.containsKey("startLine") ? toInt(args.get("startLine")) : null;
        Integer endLine   = args.containsKey("endLine")   ? toInt(args.get("endLine"))   : null;
        // ReadonlyToolService.getFileContent delegates to RepoWorkspaceResolver for path safety
        var fc = readonlyToolService.getFileContent(MCP_USER_ID, repoId, path, startLine, endLine);
        return fc.getContent();
    }

    private String doOpenFile(Map<String, Object> args) {
        String  path = requireStr(args, "path");
        Integer line = args.containsKey("line") ? toInt(args.get("line")) : null;
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("path", path);
        if (line != null) params.put("line", line);
        boolean sent = uiActionBroker.push("open_file", params);
        return sent ? "Requested frontend to open: " + path
                    : "Frontend not connected; open_file not delivered (degraded)";
    }

    private String doFocusSymbol(Map<String, Object> args) {
        Long symbolId = requireLong(args, "symbolId");
        boolean sent  = uiActionBroker.push("focus_symbol", Map.of("symbolId", symbolId));
        return sent ? "Requested frontend to focus symbol: " + symbolId
                    : "Frontend not connected; focus_symbol not delivered (degraded)";
    }

    private String doShowRequirementViz(Map<String, Object> args) {
        Long reqId  = requireLong(args, "reqId");
        boolean sent = uiActionBroker.push("show_requirement_viz", Map.of("reqId", reqId));
        return sent ? "Requested frontend to show requirement viz: " + reqId
                    : "Frontend not connected; show_requirement_viz not delivered (degraded)";
    }

    // ── JSON-RPC envelope helpers ─────────────────────────────────────────────

    private Map<String, Object> rpcResult(Object id, Object result) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("jsonrpc", "2.0");
        resp.put("id", id);
        resp.put("result", result);
        return resp;
    }

    private Map<String, Object> rpcError(Object id, int code, String message) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("jsonrpc", "2.0");
        resp.put("id", id);
        resp.put("error", Map.of("code", code, "message", message));
        return resp;
    }

    // ── Tool schema helpers ───────────────────────────────────────────────────

    @SafeVarargs
    private final Map<String, Object> schemaOf(List<String> required,
                                                Map.Entry<String, Object>... props) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map.Entry<String, Object> p : props) {
            properties.put(p.getKey(), p.getValue());
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        return schema;
    }

    private Map.Entry<String, Object> prop(String name, String type, String description) {
        return Map.entry(name, Map.of("type", type, "description", description));
    }

    private Map<String, Object> buildTool(String name, String description,
                                           Map<String, Object> inputSchema) {
        return Map.of("name", name, "description", description, "inputSchema", inputSchema);
    }

    // ── Argument helpers ──────────────────────────────────────────────────────

    private Long resolveRepoId(Map<String, Object> args) {
        if (args.containsKey("repoId") && args.get("repoId") != null) {
            return toLong(args.get("repoId"));
        }
        Long ctx = contextHolder.getCurrentRepoId();
        if (ctx != null) return ctx;
        throw new IllegalArgumentException(
                "repoId is required — provide it as a tool argument or set via POST /api/mcp/context");
    }

    private String requireStr(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) throw new IllegalArgumentException("Missing required argument: " + key);
        return v.toString();
    }

    private Long requireLong(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) throw new IllegalArgumentException("Missing required argument: " + key);
        return toLong(v);
    }

    private Long toLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s)  return Long.parseLong(s);
        throw new IllegalArgumentException("Cannot convert to Long: " + value);
    }

    private int toInt(Object value) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s)  return Integer.parseInt(s);
        throw new IllegalArgumentException("Cannot convert to int: " + value);
    }

    private boolean isLoopback(HttpServletRequest request) {
        String addr = request.getRemoteAddr();
        return "127.0.0.1".equals(addr)
                || "::1".equals(addr)
                || "0:0:0:0:0:0:0:1".equals(addr);
    }
}
