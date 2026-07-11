package com.repolens.mcp;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the enriched IDE state reported by the frontend (via POST /api/mcp/ide-state).
 *
 * <p>Distinct from {@link McpContextHolder}: that holder stores the lightweight "current repo"
 * context (repoId, bare file path, bare selection text) used by MCP tools.  This holder stores
 * the richer structured state used by MCP <em>resources</em>:
 * <ul>
 *   <li>{@code ide://active-file} → {@link ActiveFileState}: full file path + file content</li>
 *   <li>{@code ide://selection}   → {@link SelectionState}: file path + line range + selected text</li>
 * </ul>
 *
 * <p>Updated atomically; reads always return a consistent snapshot (or null if not yet set).
 * Thread-safe — no external synchronisation required.
 */
@Component
public class McpIdeStateHolder {

    private final AtomicReference<ActiveFileState> activeFile = new AtomicReference<>();
    private final AtomicReference<SelectionState>  selection  = new AtomicReference<>();

    // ── Active file ───────────────────────────────────────────────────────────

    public void setActiveFile(String filePath, String content) {
        activeFile.set(new ActiveFileState(filePath, content != null ? content : ""));
    }

    /** Returns null when no active-file state has been reported yet. */
    public ActiveFileState getActiveFile() {
        return activeFile.get();
    }

    // ── Selection ─────────────────────────────────────────────────────────────

    public void setSelection(String filePath, Integer startLine, Integer endLine, String text) {
        selection.set(new SelectionState(filePath, startLine, endLine, text != null ? text : ""));
    }

    /** Returns null when no selection state has been reported yet. */
    public SelectionState getSelection() {
        return selection.get();
    }

    // ── Value types ───────────────────────────────────────────────────────────

    /**
     * Snapshot of the currently active file in the editor.
     *
     * @param filePath relative or absolute file path
     * @param content  full file content at the time of the report
     */
    public record ActiveFileState(String filePath, String content) {}

    /**
     * Snapshot of the current editor selection.
     *
     * @param filePath  the file the selection is in
     * @param startLine 1-based inclusive start line (may be null if not provided)
     * @param endLine   1-based inclusive end line (may be null if not provided)
     * @param text      selected text
     */
    public record SelectionState(String filePath, Integer startLine, Integer endLine, String text) {}
}
