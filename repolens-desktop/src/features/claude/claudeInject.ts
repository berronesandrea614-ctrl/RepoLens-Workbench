/**
 * claudeInject.ts — Pure helpers for constructing the text to inject into a
 * running Claude Code PTY when the user clicks "发到 Claude" in the editor.
 *
 * No Tauri / React / browser dependencies — fully unit-testable with vitest.
 */

// ─────────────────────────────────────────────────────────────
//  Injection text construction
// ─────────────────────────────────────────────────────────────

/**
 * Build the text to write into the Claude Code PTY.
 *
 * - No selection  → `@<relativePath> ` (trailing space so the user can keep
 *   typing or press Enter to send).
 * - With selection → a fenced code block whose info-string is the filename,
 *   followed by a newline so the block is properly closed for Claude.
 *
 * @param relativePath  File path relative to the project's realDir (e.g. "src/App.tsx").
 * @param selection     Selected text from the editor, or undefined / empty string.
 */
export function buildInjectionText(
  relativePath: string,
  selection?: string,
): string {
  if (!selection || selection.trim() === "") {
    // Reference the whole file via Claude's @ syntax.
    return `@${relativePath} `;
  }

  // Fenced code block — Claude's markdown renderer uses the info-string (after ```)
  // to identify the language / filename.  We use the basename only, matching the
  // common convention of ```` ```App.tsx ```` in Claude prompts.
  const filename = relativePath.split("/").pop() ?? relativePath;
  return `\`\`\`${filename}\n${selection}\n\`\`\`\n`;
}
