/**
 * projectSwitcherHelpers.ts — Pure, side-effect-free helpers for ClaudeProjectSwitcher.
 *
 * All functions are independently testable with vitest.
 */

import type { RepoVO } from "../../api/repoApi";
import { repoRealDir } from "./realDir";

// ─────────────────────────────────────────────────────────────
//  Stable avatar colour
// ─────────────────────────────────────────────────────────────

/**
 * Generate a stable HSL background colour from a repo name.
 *
 * Uses a 32-bit polynomial hash of the name's char-codes so that
 * the same name always maps to the same hue (deterministic / no RNG).
 *
 * Output:  hsl(<0–359>, 55%, 38%)
 */
export function avatarColor(name: string): string {
  let hash = 0;
  for (let i = 0; i < name.length; i++) {
    hash = ((hash * 31 + name.charCodeAt(i)) >>> 0) & 0xffffff;
  }
  const hue = hash % 360;
  return `hsl(${hue}, 55%, 38%)`;
}

// ─────────────────────────────────────────────────────────────
//  Avatar initial
// ─────────────────────────────────────────────────────────────

/**
 * Return the first visible character (uppercased) of a repo name.
 * Falls back to "?" for empty / whitespace-only strings.
 */
export function initial(name: string): string {
  const trimmed = name.trim();
  if (!trimmed) return "?";
  return trimmed[0].toUpperCase();
}

// ─────────────────────────────────────────────────────────────
//  Project filter
// ─────────────────────────────────────────────────────────────

/**
 * Filter a repo list by a case-insensitive query.
 *
 * Match order (short-circuits on first hit):
 *   1. repoName
 *   2. real local directory (file:// URL decoded via repoRealDir)
 *   3. raw repoUrl  (for remote/snapshot repos that have no local dir)
 *
 * Returns the full list unchanged when the query is blank / whitespace-only.
 */
export function filterProjects(list: RepoVO[], query: string): RepoVO[] {
  const q = query.trim().toLowerCase();
  if (!q) return list;
  return list.filter((r) => {
    if (r.repoName.toLowerCase().includes(q)) return true;
    const dir = repoRealDir(r);
    if (dir) {
      return dir.toLowerCase().includes(q);
    }
    if (r.repoUrl) {
      return r.repoUrl.toLowerCase().includes(q);
    }
    return false;
  });
}
