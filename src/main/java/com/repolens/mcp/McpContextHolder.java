package com.repolens.mcp;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds MCP session context: the "current" repo and IDE state (active file, selection).
 * Updated by the frontend via POST /api/mcp/context.
 * Read by MCP tools to resolve the default repoId when the caller omits it.
 */
@Component
public class McpContextHolder {

    private final AtomicReference<Long> currentRepoId = new AtomicReference<>();
    private final AtomicReference<String> activeFilePath = new AtomicReference<>();
    private final AtomicReference<String> selectionText = new AtomicReference<>();

    public void setCurrentRepoId(Long repoId) {
        currentRepoId.set(repoId);
    }

    public Long getCurrentRepoId() {
        return currentRepoId.get();
    }

    public void setActiveFilePath(String path) {
        activeFilePath.set(path);
    }

    public String getActiveFilePath() {
        return activeFilePath.get();
    }

    public void setSelectionText(String text) {
        selectionText.set(text);
    }

    public String getSelectionText() {
        return selectionText.get();
    }
}
