/**
 * feishuOutputReporter.ts — PTY → Feishu uplink reporter.
 *
 * When a repoId has a CONNECTED Feishu binding, PTY output chunks are
 * throttled (500 ms) and forwarded to POST /api/repos/{repoId}/feishu/pty-output.
 *
 * Design:
 *  - Fire-and-forget: network errors are swallowed silently.
 *  - Throttled: chunks accumulate in a buffer; flushed at most once per 500 ms.
 *  - Guard: if no CONNECTED binding exists, chunks are dropped immediately —
 *    no timer, no buffering, no network call.
 *
 * All pure helpers are exported for unit testing.
 */

import { reportPtyOutput, type FeishuBinding } from "../../api/feishuApi";

// ─── Pure helpers (testable without HTTP) ─────────────────────────────────────

/**
 * Assemble the data string that should be written to the PTY for a
 * feishu_input action: append a carriage return so the command is submitted.
 *
 * This is the canonical helper used by both the reporter and the MCP receiver.
 */
export function buildTermWriteData(text: string): string {
  return text + "\r";
}

/**
 * Returns true if at least one binding in the list has status "CONNECTED".
 * Pure — usable in tests without mocking the store.
 */
export function hasConnectedBinding(bindings: FeishuBinding[]): boolean {
  return bindings.some((b) => b.status === "CONNECTED");
}

// ─── Reporter factory ─────────────────────────────────────────────────────────

/**
 * Create a throttled PTY→Feishu uplink reporter for a given repoId.
 *
 * @param repoId       The repo whose PTY output to forward.
 * @param getBindings  Called on each chunk to read current feishu bindings
 *                     (e.g. `() => useClaudeStore.getState().feishuBindings[repoId] ?? []`).
 *                     Zero-copy — called synchronously, must not block.
 * @param throttleMs   Maximum flush interval in milliseconds (default 500).
 * @returns            Object with `report(chunk)` method.
 */
export function createFeishuReporter(
  repoId: number,
  getBindings: () => FeishuBinding[],
  throttleMs = 500,
): { report: (chunk: string) => void } {
  let pending = "";
  let timer: ReturnType<typeof setTimeout> | null = null;

  function flush() {
    if (!pending) {
      timer = null;
      return;
    }
    // Re-check bindings at flush time — user may have removed the binding.
    const bindings = getBindings();
    if (!hasConnectedBinding(bindings)) {
      pending = "";
      timer = null;
      return;
    }
    const chunk = pending;
    pending = "";
    timer = null;
    // fire-and-forget — failures must not affect PTY or UI.
    reportPtyOutput(repoId, chunk).catch(() => {});
  }

  function report(chunk: string) {
    // Fast-path: skip entirely if no CONNECTED binding (avoids timer overhead).
    const bindings = getBindings();
    if (!hasConnectedBinding(bindings)) return;

    pending += chunk;
    if (timer === null) {
      timer = setTimeout(flush, throttleMs);
    }
  }

  return { report };
}
