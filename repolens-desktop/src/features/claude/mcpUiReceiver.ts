/**
 * MCP UI-action receiver for the Claude Code panel.
 *
 * Subscribes to GET /api/mcp/ui-events (SSE) and dispatches UI actions:
 *   - open_file(path, line?)       → workbenchStore.openFile + revealTarget
 *   - focus_symbol(symbolId)       → workbenchStore.setView("graph")
 *   - show_requirement_viz(reqId)  → workbenchStore.openRequirementInsight(reqId)
 *
 * Usage: call startMcpUiReceiver() when the Claude Code panel mounts;
 * call the returned cleanup function when it unmounts.
 */

import { type McpUiActionHandler, subscribeMcpUiEvents } from "../../api/mcpApi";
import { useWorkbench } from "../../state/workbenchStore";

/**
 * Start the MCP UI-action SSE subscription.
 *
 * @returns cleanup function to unsubscribe
 */
export function startMcpUiReceiver(): () => void {
  const store = useWorkbench.getState();

  const handler: McpUiActionHandler = (event) => {
    switch (event.action) {
      case "open_file": {
        const path = event.params["path"] as string | undefined;
        const line = event.params["line"] as number | undefined;
        if (path) {
          store.openFile(path, line);
        }
        break;
      }

      case "focus_symbol": {
        // Navigate to graph view; the full implementation would scroll
        // to the symbol node — wired via future CodeGraphPanel integration.
        store.setView("graph");
        break;
      }

      case "show_requirement_viz": {
        // Open the specific requirement insight card if a reqId is provided.
        const reqId = event.params["reqId"] as number | undefined;
        if (reqId != null) {
          store.openRequirementInsight(reqId);
        } else {
          // Fallback: navigate to the requirements list view.
          store.setView("requirements");
        }
        break;
      }

      case "feishu_input": {
        // Feishu → PTY downlink: write the incoming text to the active PTY.
        // ptyId is preferred; fall back to the convention 10000+repoId.
        const ptyId =
          (event.params["ptyId"] as number | undefined) ??
          10000 + ((event.params["repoId"] as number | undefined) ?? 0);
        const text = event.params["text"] as string | undefined;
        if (text != null) {
          import("@tauri-apps/api/core")
            .then(({ invoke }) => {
              void invoke("term_write", { id: ptyId, data: text + "\r" });
            })
            .catch(() => {
              // Non-Tauri environment or invoke failure — ignore silently.
            });
        }
        break;
      }

      default:
        break;
    }
  };

  const unsubscribe = subscribeMcpUiEvents(null, handler, (err) => {
    // Log but don't crash — MCP UI events are best-effort
    console.warn("[McpUiReceiver] SSE error:", err);
  });

  return unsubscribe;
}
