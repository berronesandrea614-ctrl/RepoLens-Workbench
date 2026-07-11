/**
 * externalChangesFlow.ts — Pure helpers for the external-changes → insight loop.
 *
 * When the Claude Code engine is active and Rust emits `repo-file-changed` events,
 * the panel debounce-aggregates the changed paths and calls the backend
 * external-changes/summarize endpoint, then opens the requirement insight card.
 *
 * All pure helpers here are side-effect-free and fully unit-testable with vitest.
 * The ClaudeCodePanel wires the side effects (fetch + store actions).
 */

import { shouldIgnorePath } from "./watchLogic";

// ─────────────────────────────────────────────────────────────
//  Path filtering
// ─────────────────────────────────────────────────────────────

/**
 * Filter out paths that should be ignored by the external-changes summarizer.
 * Applies the same ignore rules as the file-watcher (shouldIgnorePath).
 */
export function filterChangedPaths(paths: string[]): string[] {
  return paths.filter((p) => !shouldIgnorePath(p));
}

// ─────────────────────────────────────────────────────────────
//  Debounce accumulator
// ─────────────────────────────────────────────────────────────

/**
 * Debounce accumulator state.  Holds a timer handle and the accumulated
 * set of changed paths waiting to be flushed.
 *
 * Exported so callers can hold a stable reference across re-renders.
 */
export interface DebounceAccumulator {
  timer: ReturnType<typeof setTimeout> | null;
  pending: Set<string>;
}

export function makeAccumulator(): DebounceAccumulator {
  return { timer: null, pending: new Set() };
}

/**
 * Add new paths to the accumulator and (re)start the debounce timer.
 *
 * When the timer fires, `flush` is called with the accumulated unique paths.
 * The accumulator is reset after flush.
 *
 * @param acc       Mutable accumulator (shared across calls).
 * @param newPaths  New paths to add to the pending set.
 * @param delayMs   Debounce window in ms (default 1500).
 * @param flush     Callback invoked with deduplicated paths after the window elapses.
 */
export function debounceAccumulate(
  acc: DebounceAccumulator,
  newPaths: string[],
  delayMs: number,
  flush: (paths: string[]) => void,
): void {
  // Filter before accumulating.
  const filtered = filterChangedPaths(newPaths);
  if (filtered.length === 0) return;

  for (const p of filtered) {
    acc.pending.add(p);
  }

  if (acc.timer != null) {
    clearTimeout(acc.timer);
  }
  acc.timer = setTimeout(() => {
    acc.timer = null;
    const paths = [...acc.pending];
    acc.pending.clear();
    flush(paths);
  }, delayMs);
}

/**
 * Cancel any pending timer and clear the accumulator.
 */
export function cancelAccumulator(acc: DebounceAccumulator): void {
  if (acc.timer != null) {
    clearTimeout(acc.timer);
    acc.timer = null;
  }
  acc.pending.clear();
}

// ─────────────────────────────────────────────────────────────
//  Relative-path extraction
// ─────────────────────────────────────────────────────────────

/**
 * Convert absolute paths from repo-file-changed to relative paths under realDir.
 * Paths outside realDir are dropped.
 */
export function toRelativePaths(absPaths: string[], realDir: string): string[] {
  const root = realDir.replace(/\\/g, "/").replace(/\/?$/, "/");
  return absPaths
    .map((p) => {
      const norm = p.replace(/\\/g, "/");
      if (!norm.startsWith(root)) return null;
      return norm.slice(root.length) || null;
    })
    .filter((p): p is string => p != null);
}
