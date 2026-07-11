/**
 * claudeGuidanceDetect.ts — Pure helpers for detecting when claude CLI is
 * not installed or not logged in from PTY output.
 *
 * Exported as pure functions so they can be unit-tested with vitest without
 * any DOM or Tauri APIs.
 *
 * Time-gating: use `detectGuidanceKindWithinWindow` in production to avoid
 * false-positive banners triggered by shell errors the user runs after startup.
 */

// ─────────────────────────────────────────────────────────────
//  Keyword detection
// ─────────────────────────────────────────────────────────────

/** Keywords that indicate `claude` is not installed. */
const NOT_INSTALLED_KEYWORDS = [
  "command not found",
  ": not found",        // bash output: "bash: claude: not found"
  "No such file or directory",
];

/** Keywords that indicate the user is not logged in / has invalid credentials. */
const NOT_LOGGED_IN_KEYWORDS = [
  "not logged in",
  "Please log in",
  "Invalid API key",
  "Authentication failed",
  "You are not authenticated",
  "API key not found",
  "ANTHROPIC_API_KEY",
];

export type GuidanceKind = "not-installed" | "not-logged-in" | null;

/**
 * Scan a PTY output chunk for signals that claude CLI is not installed or
 * not logged in.
 *
 * Returns:
 *   - `"not-installed"` if the output matches a not-installed keyword
 *   - `"not-logged-in"` if the output matches a not-logged-in keyword
 *   - `null` if no signal found
 *
 * Case-insensitive match.
 */
export function detectGuidanceKind(output: string): GuidanceKind {
  const lower = output.toLowerCase();
  for (const kw of NOT_INSTALLED_KEYWORDS) {
    if (lower.includes(kw.toLowerCase())) return "not-installed";
  }
  for (const kw of NOT_LOGGED_IN_KEYWORDS) {
    if (lower.includes(kw.toLowerCase())) return "not-logged-in";
  }
  return null;
}

// ─────────────────────────────────────────────────────────────
//  Time-gated detection (startup window only)
// ─────────────────────────────────────────────────────────────

/**
 * Time-gated wrapper around `detectGuidanceKind`.
 *
 * Only performs keyword detection when the PTY chunk arrives within
 * `windowMs` milliseconds of the process spawn.  After the window closes the
 * function always returns `null`, preventing false-positive banners caused by
 * "command not found" / "No such file or directory" output from arbitrary
 * shell commands the user runs inside the PTY later.
 *
 * @param output   Raw PTY output chunk.
 * @param spawnTime Timestamp (ms) recorded just before `term_spawn` was called.
 * @param windowMs  How long after spawn to keep scanning (default 5 000 ms).
 * @param nowMs     Current timestamp — injectable so unit tests can be deterministic.
 */
export function detectGuidanceKindWithinWindow(
  output: string,
  spawnTime: number,
  windowMs = 5_000,
  nowMs = Date.now(),
): GuidanceKind {
  // Guard: treat non-finite or zero spawnTime as "PTY not yet started" →
  // window closed.  This covers the component's `?? Infinity` sentinel as well
  // as any zero/negative/NaN value that could arrive from a future refactor.
  if (!isFinite(spawnTime) || spawnTime <= 0) return null;
  if (nowMs - spawnTime >= windowMs) return null;
  return detectGuidanceKind(output);
}

// ─────────────────────────────────────────────────────────────
//  Guidance messages
// ─────────────────────────────────────────────────────────────

/** User-facing install command hint. */
export const INSTALL_HINT =
  "npm i -g @anthropic-ai/claude-code  然后运行 claude 完成登录";

/** User-facing login hint. */
export const LOGIN_HINT = "运行 claude 并按提示完成 Anthropic 账号登录";

/**
 * Returns a user-facing guidance message for the given GuidanceKind.
 * Returns null for kind=null.
 */
export function guidanceMessage(kind: GuidanceKind): string | null {
  if (kind === "not-installed") {
    return `Claude Code CLI 未安装。安装命令：${INSTALL_HINT}`;
  }
  if (kind === "not-logged-in") {
    return `Claude Code 未登录。${LOGIN_HINT}`;
  }
  return null;
}
