/**
 * watchLogic.ts — Pure helpers for the file-watcher frontend layer.
 *
 * All functions are side-effect-free and fully unit-testable with vitest.
 * The ClaudeCodePanel imports these to process `repo-file-changed` events.
 */

// ─────────────────────────────────────────────────────────────
//  Ignore-rule matching
// ─────────────────────────────────────────────────────────────

/**
 * Return true when the file path should be suppressed before reaching the
 * frontend.  Mirrors the Rust-side ignore rules in repo_watch_start.
 *
 * Rules (order matters for performance — cheapest first):
 *   1. Any path segment that equals a known noisy directory name.
 *   2. The filename `.DS_Store`.
 *
 * "Segment" = a slash-delimited component, so "nodist/file.ts" does NOT
 * match the /dist/ rule — only "/dist/" or a path that ends with "/dist".
 */
export function shouldIgnorePath(path: string): boolean {
  const noisyDirs = [".git", "node_modules", "target", "dist", ".idea"];

  // Normalise to forward slashes for cross-platform consistency.
  const p = path.replace(/\\/g, "/");

  for (const dir of noisyDirs) {
    if (p.includes(`/${dir}/`) || p.endsWith(`/${dir}`)) {
      return true;
    }
    // Handle root-relative paths like ".git/config" (no leading slash).
    if (p === dir || p.startsWith(`${dir}/`)) {
      return true;
    }
  }

  // Check file name only (last segment).
  const basename = p.split("/").pop() ?? "";
  if (basename === ".DS_Store") {
    return true;
  }

  return false;
}

// ─────────────────────────────────────────────────────────────
//  Conflict / reload classification
// ─────────────────────────────────────────────────────────────

export interface Tab {
  path: string;   // relative path as stored in workbenchStore
  dirty: boolean;
}

export interface FileChangedClassification {
  /** Relative paths of open-clean files that should be auto-reloaded from disk. */
  toReload: string[];
  /** Relative paths of open-dirty files that need a user-facing conflict prompt. */
  toConflict: string[];
}

/**
 * Classify externally-changed absolute paths against the set of currently
 * open editor tabs.
 *
 * @param changedAbsPaths  Absolute paths received from `repo-file-changed`.
 * @param openTabs         Currently open editor tabs (relative paths).
 * @param realDir          Project root (absolute) used to relativise the watch paths.
 *
 * A changed path is "relevant" when its relative form matches a tab path.
 * If the tab is clean  → toReload.
 * If the tab is dirty  → toConflict.
 */
export function classifyChangedFiles(
  changedAbsPaths: string[],
  openTabs: Tab[],
  realDir: string,
): FileChangedClassification {
  const toReload: string[] = [];
  const toConflict: string[] = [];

  // Normalise realDir to have a trailing slash for easy prefix stripping.
  const root = realDir.replace(/\\/g, "/").replace(/\/?$/, "/");

  for (const absPath of changedAbsPaths) {
    const norm = absPath.replace(/\\/g, "/");
    if (!norm.startsWith(root)) continue;

    const relPath = norm.slice(root.length);
    if (!relPath) continue;

    const tab = openTabs.find((t) => t.path === relPath);
    if (!tab) continue;

    if (tab.dirty) {
      toConflict.push(relPath);
    } else {
      toReload.push(relPath);
    }
  }

  return { toReload, toConflict };
}
