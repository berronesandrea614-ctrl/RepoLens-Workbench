import type { RepoVO } from "../../api/repoApi";

/**
 * Deduplicate repos by real local directory.
 *
 * When multiple repos share the same realDir (e.g. the user imported the same
 * folder twice), only the first occurrence (lowest list position) is kept.
 * Repos without a real dir (remote/snapshot) are always kept as-is.
 *
 * Used by ClaudeProjectSwitcher to avoid showing duplicate rows for the same
 * directory, which would confuse the user and trigger duplicate-spawn issues.
 */
export function dedupeByRealDir(repos: RepoVO[]): RepoVO[] {
  const seen = new Set<string>();
  return repos.filter((r) => {
    const dir = repoRealDir(r);
    if (dir == null) return true; // not a local repo — always include
    if (seen.has(dir)) return false; // duplicate local dir — skip
    seen.add(dir);
    return true;
  });
}

/**
 * Extract the real local directory path from a repo's repoUrl.
 *
 * - file:// URLs (local open-folder imports): decode and strip "file://" prefix → real path.
 * - Remote URLs (http/https/git remote): return null — no local working tree, Claude Code unavailable.
 * - Missing/empty repoUrl: return null.
 *
 * Handles URL-encoded paths including Chinese characters.
 */
export function repoRealDir(repo: RepoVO | null | undefined): string | null {
  const url = repo?.repoUrl;
  if (!url) return null;
  if (!url.startsWith("file://")) return null;
  // "file:///abs/path" → "/abs/path"
  // "file:///Users/%E9%A1%B9%E7%9B%AE/repo" → "/Users/项目/repo"
  try {
    const path = decodeURIComponent(url.slice("file://".length));
    return path || null;
  } catch {
    // 损坏的百分号编码不应让渲染崩溃，视为无真实目录。
    return null;
  }
}
