package com.repolens.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Broker that forwards MCP UI-action tool calls to the connected frontend via SSE.
 *
 * <p>The frontend Claude panel subscribes via GET /api/mcp/ui-events.
 * When McpController dispatches an open_file / focus_symbol / show_requirement_viz
 * tool call, it calls {@link #push} here; the broker emits a Server-Sent Event
 * on the registered emitter.
 *
 * <p>Thread-safety: AtomicReference ensures safe publish/replace of the emitter.
 * At most one frontend connection is active at a time (re-registering replaces the old one).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpUiActionBroker {

    private final AtomicReference<SseEmitter> emitterRef = new AtomicReference<>();
    private final ObjectMapper objectMapper;

    /**
     * Called by GET /api/mcp/ui-events: creates and registers a new SseEmitter
     * for the frontend to receive UI actions.
     */
    public SseEmitter register() {
        SseEmitter newEmitter = new SseEmitter(0L); // no timeout — kept alive until frontend disconnects
        SseEmitter old = emitterRef.getAndSet(newEmitter);
        if (old != null) {
            try { old.complete(); } catch (Exception ignored) {}
        }
        newEmitter.onCompletion(() -> emitterRef.compareAndSet(newEmitter, null));
        newEmitter.onTimeout(() -> emitterRef.compareAndSet(newEmitter, null));
        newEmitter.onError(e -> emitterRef.compareAndSet(newEmitter, null));
        return newEmitter;
    }

    /**
     * Push a UI action event to the connected frontend.
     *
     * @param action e.g. "open_file", "focus_symbol", "show_requirement_viz"
     * @param params tool arguments as a map
     * @return true if event was sent, false if no frontend is connected (graceful degradation)
     */
    public boolean push(String action, Map<String, Object> params) {
        SseEmitter current = emitterRef.get();
        if (current == null) {
            log.debug("[McpUiAction] No frontend connected, dropping action: {}", action);
            return false;
        }
        try {
            Map<String, Object> payload = Map.of("action", action, "params", params);
            String json = objectMapper.writeValueAsString(payload);
            current.send(SseEmitter.event().name("ui-action").data(json));
            log.debug("[McpUiAction] Pushed action: {}", action);
            return true;
        } catch (Exception e) {
            log.warn("[McpUiAction] Failed to push action {}: {}", action, e.getMessage());
            emitterRef.compareAndSet(current, null);
            return false;
        }
    }

    /** Exposed for tests. */
    boolean hasActiveEmitter() {
        return emitterRef.get() != null;
    }
}
