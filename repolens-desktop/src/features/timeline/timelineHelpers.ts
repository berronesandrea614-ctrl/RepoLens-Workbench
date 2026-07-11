/**
 * Pure helper functions for timeline scrubber — no React, no side-effects.
 * Exported so they can be unit-tested without a DOM environment.
 */

/**
 * Advance one frame forward, clamping at the last valid index.
 * @param idx   Current zero-based frame index.
 * @param total Total number of frames.
 */
export function nextFrame(idx: number, total: number): number {
  if (total <= 0) return 0;
  return Math.min(idx + 1, total - 1);
}

/**
 * Go one frame back, clamping at 0.
 * @param idx   Current zero-based frame index.
 * @param total Total number of frames (not used for clamping, kept for symmetry).
 */
export function prevFrame(idx: number, _total: number): number {
  return Math.max(idx - 1, 0);
}
