import { describe, it, expect } from "vitest";
import {
  detectGuidanceKind,
  detectGuidanceKindWithinWindow,
  guidanceMessage,
  INSTALL_HINT,
  LOGIN_HINT,
} from "./claudeGuidanceDetect";

// ─────────────────────────────────────────────────────────────
//  detectGuidanceKind
// ─────────────────────────────────────────────────────────────

describe("detectGuidanceKind", () => {
  // ── Not-installed signals ──────────────────────────────────

  it("detects 'command not found'", () => {
    expect(detectGuidanceKind("zsh: command not found: claude")).toBe("not-installed");
  });

  it("detects 'bash: claude: not found' (bash output)", () => {
    expect(detectGuidanceKind("bash: claude: not found")).toBe("not-installed");
  });

  it("detects 'No such file or directory'", () => {
    expect(detectGuidanceKind(
      "exec: 'claude': No such file or directory",
    )).toBe("not-installed");
  });

  it("is case-insensitive for not-installed keywords", () => {
    expect(detectGuidanceKind("COMMAND NOT FOUND")).toBe("not-installed");
  });

  // ── Not-logged-in signals ─────────────────────────────────

  it("detects 'not logged in'", () => {
    expect(detectGuidanceKind("Error: not logged in. Please log in first.")).toBe("not-logged-in");
  });

  it("detects 'Please log in'", () => {
    expect(detectGuidanceKind("Please log in to continue.")).toBe("not-logged-in");
  });

  it("detects 'Invalid API key'", () => {
    expect(detectGuidanceKind("Error: Invalid API key provided.")).toBe("not-logged-in");
  });

  it("detects 'Authentication failed'", () => {
    expect(detectGuidanceKind("Authentication failed. Check your credentials.")).toBe("not-logged-in");
  });

  it("detects 'You are not authenticated'", () => {
    expect(detectGuidanceKind("You are not authenticated. Run claude login.")).toBe("not-logged-in");
  });

  it("detects 'API key not found'", () => {
    expect(detectGuidanceKind("API key not found in environment.")).toBe("not-logged-in");
  });

  it("detects 'ANTHROPIC_API_KEY' mention (key not set)", () => {
    expect(detectGuidanceKind("ANTHROPIC_API_KEY is not set.")).toBe("not-logged-in");
  });

  it("is case-insensitive for not-logged-in keywords", () => {
    expect(detectGuidanceKind("INVALID API KEY")).toBe("not-logged-in");
  });

  // ── No signal ─────────────────────────────────────────────

  it("returns null for normal PTY output", () => {
    expect(detectGuidanceKind("Welcome to Claude Code!")).toBeNull();
  });

  it("returns null for empty string", () => {
    expect(detectGuidanceKind("")).toBeNull();
  });

  it("returns null for unrelated output", () => {
    expect(detectGuidanceKind("Fetching repository...")).toBeNull();
  });

  it("prioritises not-installed over not-logged-in (not-installed checked first)", () => {
    // Both keywords present — first match wins (not-installed).
    const out = "command not found and invalid api key";
    expect(detectGuidanceKind(out)).toBe("not-installed");
  });
});

// ─────────────────────────────────────────────────────────────
//  detectGuidanceKindWithinWindow — time-gated detection
// ─────────────────────────────────────────────────────────────

describe("detectGuidanceKindWithinWindow", () => {
  // Use fixed timestamps so tests are deterministic (no real Date.now()).
  const SPAWN_TIME = 10_000; // arbitrary fixed spawn time
  const WINDOW = 5_000;

  // ── Within window ─────────────────────────────────────────

  it("triggers not-installed when keyword found within startup window", () => {
    const nowMs = SPAWN_TIME + 1_000; // 1 s after spawn — well inside window
    expect(
      detectGuidanceKindWithinWindow(
        "zsh: command not found: claude",
        SPAWN_TIME,
        WINDOW,
        nowMs,
      ),
    ).toBe("not-installed");
  });

  it("triggers not-logged-in when keyword found within startup window", () => {
    const nowMs = SPAWN_TIME + 4_999; // 4 999 ms — just inside window
    expect(
      detectGuidanceKindWithinWindow(
        "Error: not logged in. Please log in first.",
        SPAWN_TIME,
        WINDOW,
        nowMs,
      ),
    ).toBe("not-logged-in");
  });

  it("returns null for normal output even within window", () => {
    const nowMs = SPAWN_TIME + 1_000;
    expect(
      detectGuidanceKindWithinWindow(
        "Welcome to Claude Code!",
        SPAWN_TIME,
        WINDOW,
        nowMs,
      ),
    ).toBeNull();
  });

  // ── Outside window (false-positive prevention) ────────────

  it("does NOT trigger guidance when keyword found after window closes (5 001 ms)", () => {
    const nowMs = SPAWN_TIME + 5_001; // 1 ms past the 5 s boundary
    expect(
      detectGuidanceKindWithinWindow(
        "zsh: command not found: foo",
        SPAWN_TIME,
        WINDOW,
        nowMs,
      ),
    ).toBeNull();
  });

  it("does NOT trigger guidance exactly at the window boundary (5 000 ms)", () => {
    const nowMs = SPAWN_TIME + WINDOW; // elapsed === windowMs → closed
    expect(
      detectGuidanceKindWithinWindow(
        "No such file or directory",
        SPAWN_TIME,
        WINDOW,
        nowMs,
      ),
    ).toBeNull();
  });

  it("does NOT trigger not-logged-in for user shell error after 10 s", () => {
    const nowMs = SPAWN_TIME + 10_000;
    expect(
      detectGuidanceKindWithinWindow(
        "API key not found in environment.",
        SPAWN_TIME,
        WINDOW,
        nowMs,
      ),
    ).toBeNull();
  });

  // ── spawnTime = Infinity guard (timestamp not yet recorded) ─

  it("returns null when spawnTime is Infinity (PTY not yet spawned)", () => {
    const nowMs = 1_000;
    expect(
      detectGuidanceKindWithinWindow(
        "command not found",
        Infinity,
        WINDOW,
        nowMs,
      ),
    ).toBeNull();
  });
});

// ─────────────────────────────────────────────────────────────
//  guidanceMessage
// ─────────────────────────────────────────────────────────────

describe("guidanceMessage", () => {
  it("returns install message for not-installed", () => {
    const msg = guidanceMessage("not-installed");
    expect(msg).not.toBeNull();
    expect(msg).toContain(INSTALL_HINT);
  });

  it("returns login message for not-logged-in", () => {
    const msg = guidanceMessage("not-logged-in");
    expect(msg).not.toBeNull();
    expect(msg).toContain(LOGIN_HINT);
  });

  it("returns null for null kind", () => {
    expect(guidanceMessage(null)).toBeNull();
  });
});
