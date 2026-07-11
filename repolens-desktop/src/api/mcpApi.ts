/**
 * MCP integration API client.
 *
 * Provides:
 *  - fetchMcpToken()        — GET /api/mcp/token (JWT-authed)
 *  - setMcpContext()        — POST /api/mcp/context (JWT-authed)
 *  - buildMcpJsonContent()  — assemble .mcp.json content given a token
 *  - subscribeMcpUiEvents() — open SSE on GET /api/mcp/ui-events, dispatch UI actions
 */

import { http } from "./http";

const BASE = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080";

// ── Token ────────────────────────────────────────────────────────────────────

/** Fetch the MCP server token from the backend (JWT-authed). */
export async function fetchMcpToken(): Promise<string> {
  const res = await http.get<{ code: number; data: string }>("/api/mcp/token");
  return res.data.data;
}

// ── Context ───────────────────────────────────────────────────────────────────

export interface McpContext {
  repoId?: number;
  activeFilePath?: string | null;
  selectionText?: string | null;
}

/** Update the MCP context (current repo, active file, selection). */
export async function setMcpContext(ctx: McpContext): Promise<void> {
  await http.post("/api/mcp/context", ctx);
}

// ── IDE state ─────────────────────────────────────────────────────────────────

export interface IdeActiveFile {
  filePath: string;
  content: string;
}

export interface IdeSelection {
  filePath: string;
  startLine?: number;
  endLine?: number;
  text: string;
}

export interface IdeState {
  activeFile?: IdeActiveFile | null;
  selection?: IdeSelection | null;
}

/**
 * Report the current editor IDE state to the backend so MCP resources
 * (ide://active-file, ide://selection) can serve it to Claude.
 * Failure-safe — network errors are swallowed (best-effort).
 */
export async function setIdeState(state: IdeState): Promise<void> {
  await http.post("/api/mcp/ide-state", state);
}

// ── .mcp.json ─────────────────────────────────────────────────────────────────

/**
 * Build the .mcp.json file content that points Claude Code at this app's MCP server.
 *
 * Merge strategy (MVP): generate the full content; the caller writes it via
 * Tauri write_text_file.  If a .mcp.json already exists the repolens entry is
 * replaced (whole file overwrite).  A future improvement would read-then-merge,
 * but for MVP single-user local use overwriting is safe and predictable.
 */
export function buildMcpJsonContent(token: string): string {
  const config = {
    mcpServers: {
      repolens: {
        type: "http",
        url: "http://127.0.0.1:8080/mcp",
        headers: {
          "X-RepoLens-Token": token,
        },
      },
    },
  };
  return JSON.stringify(config, null, 2);
}

// ── .gitignore guard ─────────────────────────────────────────────────────────

/**
 * Ensure `.mcp.json` is listed in the repo's `.gitignore`.
 *
 * Called alongside write_text_file when writing .mcp.json, so that the MCP
 * token (embedded in .mcp.json) is not accidentally committed to version control.
 *
 * Strategy: read existing .gitignore (if any), check for the entry, append if
 * missing, then write back via write_text_file.
 *
 * Safe to call even if .gitignore does not exist — it will be created.
 *
 * @param realDir  The repo's real (absolute) directory path.
 */
export async function ensureMcpJsonGitignored(realDir: string): Promise<void> {
  const IS_TAURI = "__TAURI_INTERNALS__" in window;
  if (!IS_TAURI) return;

  const { invoke } = await import("@tauri-apps/api/core");
  const gitignorePath = realDir.replace(/\/$/, "") + "/.gitignore";

  let existing = "";
  try {
    existing = (await invoke("read_text_file", {
      path: gitignorePath,
      baseDir: realDir,
    })) as string;
  } catch {
    // .gitignore does not exist yet — we will create it.
  }

  // Check if any line already covers .mcp.json (exact match or glob).
  const lines = existing.split("\n");
  const alreadyCovered = lines.some(
    (l) => l.trim() === ".mcp.json" || l.trim() === "*.mcp.json",
  );
  if (alreadyCovered) return;

  // Append the entry with a trailing newline.
  const separator = existing.length > 0 && !existing.endsWith("\n") ? "\n" : "";
  const updated = existing + separator + ".mcp.json\n";

  await invoke("write_text_file", {
    path: gitignorePath,
    baseDir: realDir,
    content: updated,
  });
}

// ── UI-action SSE ─────────────────────────────────────────────────────────────

export interface McpUiAction {
  action: "open_file" | "focus_symbol" | "show_requirement_viz" | "feishu_input";
  params: Record<string, unknown>;
}

export type McpUiActionHandler = (event: McpUiAction) => void;

/**
 * Subscribe to the MCP UI-action SSE stream.
 *
 * Returns an `unsubscribe` function to close the connection.
 * Only one connection is needed per Claude Code panel mount.
 */
export function subscribeMcpUiEvents(
  _token: string | null,
  onAction: McpUiActionHandler,
  onError?: (err: Event) => void,
): () => void {
  // The backend's GET /api/mcp/ui-events is JWT-authenticated.
  // EventSource does not support custom headers, so we use fetch + ReadableStream
  // and attach the JWT from localStorage as the Authorization header.

  const jwtToken = localStorage.getItem("repolens.token");
  if (!jwtToken) {
    return () => {};
  }

  let abortCtrl: AbortController | null = new AbortController();

  async function connect() {
    try {
      const response = await fetch(`${BASE}/api/mcp/ui-events`, {
        headers: {
          Authorization: `Bearer ${jwtToken}`,
          Accept: "text/event-stream",
        },
        signal: abortCtrl?.signal,
      });

      if (!response.ok || !response.body) return;

      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });

        // Parse SSE messages from buffer
        const events = buffer.split("\n\n");
        buffer = events.pop() ?? "";

        for (const event of events) {
          const lines = event.split("\n");
          let eventName = "";
          let data = "";
          for (const line of lines) {
            if (line.startsWith("event:")) eventName = line.slice(6).trim();
            if (line.startsWith("data:")) data = line.slice(5).trim();
          }
          if (eventName === "ui-action" && data) {
            try {
              const action = JSON.parse(data) as McpUiAction;
              onAction(action);
            } catch {
              // ignore malformed events
            }
          }
        }
      }
    } catch (err) {
      if (err instanceof DOMException && err.name === "AbortError") return;
      onError?.(err as Event);
    }
  }

  connect();

  return () => {
    abortCtrl?.abort();
    abortCtrl = null;
  };
}
