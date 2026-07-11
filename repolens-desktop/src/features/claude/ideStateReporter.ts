/**
 * ideStateReporter.ts — Debounced IDE state reporting to the backend.
 *
 * When the Claude Code engine is active, the editor's current file and
 * selection are reported to POST /api/mcp/ide-state so MCP resources
 * (ide://active-file, ide://selection) can serve them to Claude.
 *
 * Design principles:
 *  - Debounced: fire only after the user stops changing selection/file for a
 *    short window (default 400ms).  NOT on every cursor move.
 *  - Triggered by: active-file switch, selection change (range change).
 *  - Failure-safe: network errors are swallowed.
 *
 * All pure helpers are exported for unit testing.
 */

import { setIdeState, type IdeActiveFile, type IdeSelection } from "../../api/mcpApi";

// ─────────────────────────────────────────────────────────────
//  Debounce helper
// ─────────────────────────────────────────────────────────────

/**
 * Create a debounced version of a function that delays invoking it until
 * after `delayMs` milliseconds have elapsed since the last invocation.
 *
 * Returns { invoke, cancel }.
 */
export function createDebounced<T>(
  fn: (arg: T) => void,
  delayMs: number,
): { invoke: (arg: T) => void; cancel: () => void } {
  let timer: ReturnType<typeof setTimeout> | null = null;
  return {
    invoke(arg: T) {
      if (timer != null) clearTimeout(timer);
      timer = setTimeout(() => {
        timer = null;
        fn(arg);
      }, delayMs);
    },
    cancel() {
      if (timer != null) {
        clearTimeout(timer);
        timer = null;
      }
    },
  };
}

// ─────────────────────────────────────────────────────────────
//  IDE state change detection
// ─────────────────────────────────────────────────────────────

export interface IdeSnapshot {
  filePath: string | null;
  selectionStart: { lineNumber: number; column: number } | null;
  selectionEnd: { lineNumber: number; column: number } | null;
}

/**
 * Returns true if the IDE state has changed in a way that warrants reporting
 * to the backend:
 *   - File path changed.
 *   - Selection range changed (start or end line changed).
 *
 * Cursor-only column movement within the same line does NOT trigger a report.
 */
export function isSignificantChange(
  prev: IdeSnapshot | null,
  next: IdeSnapshot,
): boolean {
  if (prev == null) return true; // first report
  if (prev.filePath !== next.filePath) return true;

  // Selection range change (line-level only to avoid cursor noise).
  const prevStartLine = prev.selectionStart?.lineNumber ?? null;
  const nextStartLine = next.selectionStart?.lineNumber ?? null;
  if (prevStartLine !== nextStartLine) return true;

  const prevEndLine = prev.selectionEnd?.lineNumber ?? null;
  const nextEndLine = next.selectionEnd?.lineNumber ?? null;
  if (prevEndLine !== nextEndLine) return true;

  return false;
}

// ─────────────────────────────────────────────────────────────
//  Reporter factory
// ─────────────────────────────────────────────────────────────

/**
 * Create an IDE state reporter that debounces calls to POST /api/mcp/ide-state.
 *
 * @param debounceMs  Milliseconds to wait after the last change before reporting.
 * @returns           Object with `report(activeFile, selection)` and `dispose()`.
 */
export function createIdeStateReporter(debounceMs = 400) {
  let lastSnapshot: IdeSnapshot | null = null;

  const debounced = createDebounced<{ activeFile: IdeActiveFile | null; selection: IdeSelection | null }>(
    (state) => {
      void setIdeState(state).catch(() => {
        // Swallow — best-effort, never crash the editor.
      });
    },
    debounceMs,
  );

  return {
    /**
     * Report new IDE state.  Only sends to the backend when a significant
     * change is detected (file switch or selection range change).
     */
    report(
      snapshot: IdeSnapshot,
      activeFile: IdeActiveFile | null,
      selection: IdeSelection | null,
    ): void {
      if (!isSignificantChange(lastSnapshot, snapshot)) return;
      lastSnapshot = snapshot;
      debounced.invoke({ activeFile, selection });
    },

    /** Cancel any pending debounced report and release resources. */
    dispose(): void {
      debounced.cancel();
    },
  };
}
