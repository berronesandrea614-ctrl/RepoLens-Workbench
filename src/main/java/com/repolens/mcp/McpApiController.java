package com.repolens.mcp;

import com.repolens.common.result.Result;
import com.repolens.security.AuthUserId;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.List;

/**
 * Supporting REST endpoints for the MCP integration.
 *
 * <ul>
 *   <li>GET  /api/mcp/token      – returns the in-memory MCP token (JWT-auth + loopback)</li>
 *   <li>POST /api/mcp/context    – frontend updates the current repoId / active file / selection</li>
 *   <li>POST /api/mcp/ide-state  – frontend reports enriched IDE state (active-file + selection)</li>
 *   <li>GET  /api/mcp/ui-events  – SSE channel: frontend subscribes to receive UI action events</li>
 * </ul>
 *
 * All paths are protected by the existing JWT filter (they are not in PUBLIC_PATHS).
 * /api/mcp/token and /api/mcp/ui-events additionally enforce loopback-only access.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mcp")
public class McpApiController {

    private final McpTokenHolder tokenHolder;
    private final McpContextHolder contextHolder;
    private final McpIdeStateHolder ideStateHolder;
    private final McpUiActionBroker uiActionBroker;

    /**
     * Returns the MCP token that Claude Code must send as X-RepoLens-Token.
     * Requires JWT login + loopback-only (so only the local Tauri frontend can read it).
     */
    @GetMapping("/token")
    public Result<String> getMcpToken(@AuthUserId Long userId, HttpServletRequest request) {
        enforceLoopback(request);
        log.info("[MCP] Token requested by userId={}", userId);
        return Result.success(tokenHolder.getToken());
    }

    /**
     * Frontend updates the "current repo" context so MCP tools can default to it.
     * Body: { "repoId": 1, "activeFilePath": "src/...", "selectionText": "..." }
     * All fields are optional; omitted fields leave the current context unchanged.
     */
    @PostMapping("/context")
    public Result<Void> setContext(@AuthUserId Long userId,
                                   HttpServletRequest request,
                                   @RequestBody Map<String, Object> body) {
        enforceLoopback(request);
        if (body.containsKey("repoId") && body.get("repoId") != null) {
            contextHolder.setCurrentRepoId(toLong(body.get("repoId")));
        }
        if (body.containsKey("activeFilePath")) {
            contextHolder.setActiveFilePath((String) body.get("activeFilePath"));
        }
        if (body.containsKey("selectionText")) {
            contextHolder.setSelectionText((String) body.get("selectionText"));
        }
        log.debug("[MCP] Context updated by userId={}, repoId={}", userId, contextHolder.getCurrentRepoId());
        return Result.success(null);
    }

    /**
     * Reports enriched IDE state from the Claude Code panel.
     *
     * <p>Body: {@code { "activeFile": { "filePath": "...", "content": "..." },
     * "selection": { "filePath": "...", "startLine": 1, "endLine": 5, "text": "..." } }}
     *
     * <p>All fields are optional; omitted fields leave the current state unchanged.
     * Both activeFile and selection values are used by {@code resources/read} for
     * {@code ide://active-file} and {@code ide://selection} respectively.
     *
     * <p>Requires JWT login + loopback-only (only the local Tauri frontend should call this).
     */
    @PostMapping("/ide-state")
    public Result<Void> setIdeState(@AuthUserId Long userId,
                                    HttpServletRequest request,
                                    @RequestBody Map<String, Object> body) {
        enforceLoopback(request);
        @SuppressWarnings("unchecked")
        Map<String, Object> af = body.get("activeFile") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : null;
        if (af != null) {
            String filePath = af.get("filePath") instanceof String s ? s : null;
            String content  = af.get("content")  instanceof String s ? s : "";
            if (filePath != null) {
                ideStateHolder.setActiveFile(filePath, content);
            }
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> sel = body.get("selection") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : null;
        if (sel != null) {
            String  filePath  = sel.get("filePath")  instanceof String s ? s : null;
            Integer startLine = sel.get("startLine") instanceof Number n ? n.intValue() : null;
            Integer endLine   = sel.get("endLine")   instanceof Number n ? n.intValue() : null;
            String  text      = sel.get("text")      instanceof String s ? s : "";
            if (filePath != null) {
                ideStateHolder.setSelection(filePath, startLine, endLine, text);
            }
        }
        log.debug("[MCP] ide-state updated by userId={}", userId);
        return Result.success(null);
    }

    /**
     * SSE channel for UI action events.
     * The frontend Claude panel subscribes here; backend McpController pushes events via McpUiActionBroker.
     * Requires JWT login + loopback-only.
     */
    @GetMapping(value = "/ui-events", produces = "text/event-stream")
    public SseEmitter uiEvents(@AuthUserId Long userId, HttpServletRequest request) {
        enforceLoopback(request);
        log.info("[MCP] UI-events SSE subscribed by userId={}", userId);
        return uiActionBroker.register();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void enforceLoopback(HttpServletRequest request) {
        String addr = request.getRemoteAddr();
        if (!"127.0.0.1".equals(addr) && !"::1".equals(addr) && !"0:0:0:0:0:0:0:1".equals(addr)) {
            throw new com.repolens.common.exception.BizException(
                    com.repolens.common.constants.ErrorCode.FORBIDDEN,
                    "Access restricted to loopback interface");
        }
    }

    private Long toLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) return Long.parseLong(s);
        throw new IllegalArgumentException("Cannot convert to Long: " + value);
    }
}
