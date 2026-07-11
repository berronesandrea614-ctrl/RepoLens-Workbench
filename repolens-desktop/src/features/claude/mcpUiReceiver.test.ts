import { describe, it, expect, vi } from "vitest";
import { buildMcpJsonContent } from "../../api/mcpApi";

// ── buildMcpJsonContent tests ─────────────────────────────────────────────────
// These are pure-function tests that don't require DOM or network.

describe("buildMcpJsonContent", () => {
  it("produces valid JSON with mcpServers.repolens", () => {
    const token = "abc123def456";
    const json = buildMcpJsonContent(token);
    const parsed = JSON.parse(json);
    expect(parsed).toHaveProperty("mcpServers.repolens");
    expect(parsed.mcpServers.repolens.type).toBe("http");
    expect(parsed.mcpServers.repolens.url).toBe("http://127.0.0.1:8080/mcp");
    expect(parsed.mcpServers.repolens.headers["X-RepoLens-Token"]).toBe(token);
  });

  it("embeds the provided token correctly", () => {
    const token = "deadbeef1234567890abcdef";
    const json = buildMcpJsonContent(token);
    const parsed = JSON.parse(json);
    expect(parsed.mcpServers.repolens.headers["X-RepoLens-Token"]).toBe(token);
  });

  it("output is pretty-printed JSON", () => {
    const json = buildMcpJsonContent("tok");
    // pretty-printed means it has newlines
    expect(json).toContain("\n");
  });
});

// ── McpUiAction handler dispatch logic ───────────────────────────────────────
// Test the handler dispatch logic in isolation (without real SSE/fetch).
// This mirrors the dispatch logic in mcpUiReceiver.ts.

import type { McpUiAction } from "../../api/mcpApi";

describe("McpUiAction handler dispatch", () => {
  const makeMockStore = () => ({
    openFile: vi.fn(),
    setView: vi.fn(),
    openRequirementInsight: vi.fn(),
  });

  /**
   * Mirrors the dispatch logic in mcpUiReceiver.ts — kept in sync with the
   * actual implementation so test changes and source changes stay aligned.
   */
  function dispatchAction(
    store: ReturnType<typeof makeMockStore>,
    event: McpUiAction,
  ) {
    switch (event.action) {
      case "open_file": {
        const path = event.params["path"] as string | undefined;
        const line = event.params["line"] as number | undefined;
        if (path) store.openFile(path, line);
        break;
      }
      case "focus_symbol":
        store.setView("graph");
        break;
      case "show_requirement_viz": {
        // CC-6: open specific requirement insight card when reqId is provided.
        const reqId = event.params["reqId"] as number | undefined;
        if (reqId != null) {
          store.openRequirementInsight(reqId);
        } else {
          store.setView("requirements");
        }
        break;
      }
    }
  }

  it("open_file with line calls openFile(path, line)", () => {
    const store = makeMockStore();
    dispatchAction(store, {
      action: "open_file",
      params: { path: "src/Main.java", line: 42 },
    });
    expect(store.openFile).toHaveBeenCalledWith("src/Main.java", 42);
  });

  it("open_file without line calls openFile(path, undefined)", () => {
    const store = makeMockStore();
    dispatchAction(store, { action: "open_file", params: { path: "README.md" } });
    expect(store.openFile).toHaveBeenCalledWith("README.md", undefined);
  });

  it("open_file with missing path does nothing", () => {
    const store = makeMockStore();
    dispatchAction(store, { action: "open_file", params: {} });
    expect(store.openFile).not.toHaveBeenCalled();
  });

  it("focus_symbol navigates to graph view", () => {
    const store = makeMockStore();
    dispatchAction(store, { action: "focus_symbol", params: { symbolId: 7 } });
    expect(store.setView).toHaveBeenCalledWith("graph");
  });

  it("show_requirement_viz with reqId calls openRequirementInsight", () => {
    const store = makeMockStore();
    dispatchAction(store, { action: "show_requirement_viz", params: { reqId: 3 } });
    expect(store.openRequirementInsight).toHaveBeenCalledWith(3);
    expect(store.setView).not.toHaveBeenCalled();
  });

  it("show_requirement_viz without reqId falls back to setView(requirements)", () => {
    const store = makeMockStore();
    dispatchAction(store, { action: "show_requirement_viz", params: {} });
    expect(store.openRequirementInsight).not.toHaveBeenCalled();
    expect(store.setView).toHaveBeenCalledWith("requirements");
  });
});
