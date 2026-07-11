import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { createDebounced, isSignificantChange, type IdeSnapshot } from "./ideStateReporter";

// ─────────────────────────────────────────────────────────────
//  isSignificantChange
// ─────────────────────────────────────────────────────────────

describe("isSignificantChange", () => {
  const base: IdeSnapshot = {
    filePath: "src/App.ts",
    selectionStart: { lineNumber: 5, column: 1 },
    selectionEnd: { lineNumber: 5, column: 10 },
  };

  it("returns true when prev is null (first report)", () => {
    expect(isSignificantChange(null, base)).toBe(true);
  });

  it("returns false when snapshot is identical", () => {
    expect(isSignificantChange(base, { ...base })).toBe(false);
  });

  it("returns true when file path changes", () => {
    const next = { ...base, filePath: "src/Other.ts" };
    expect(isSignificantChange(base, next)).toBe(true);
  });

  it("returns true when selection start line changes", () => {
    const next: IdeSnapshot = {
      ...base,
      selectionStart: { lineNumber: 6, column: 1 },
    };
    expect(isSignificantChange(base, next)).toBe(true);
  });

  it("returns true when selection end line changes", () => {
    const next: IdeSnapshot = {
      ...base,
      selectionEnd: { lineNumber: 8, column: 10 },
    };
    expect(isSignificantChange(base, next)).toBe(true);
  });

  it("returns false when only column changes (cursor move within line)", () => {
    const next: IdeSnapshot = {
      ...base,
      selectionStart: { lineNumber: 5, column: 15 },
      selectionEnd: { lineNumber: 5, column: 20 },
    };
    expect(isSignificantChange(base, next)).toBe(false);
  });

  it("returns true when selection goes from null to set", () => {
    const prev: IdeSnapshot = {
      filePath: "src/App.ts",
      selectionStart: null,
      selectionEnd: null,
    };
    const next: IdeSnapshot = {
      filePath: "src/App.ts",
      selectionStart: { lineNumber: 3, column: 1 },
      selectionEnd: { lineNumber: 3, column: 5 },
    };
    expect(isSignificantChange(prev, next)).toBe(true);
  });

  it("returns false when both snapshots have null selection", () => {
    const prev: IdeSnapshot = { filePath: "src/App.ts", selectionStart: null, selectionEnd: null };
    const next: IdeSnapshot = { filePath: "src/App.ts", selectionStart: null, selectionEnd: null };
    expect(isSignificantChange(prev, next)).toBe(false);
  });
});

// ─────────────────────────────────────────────────────────────
//  createDebounced
// ─────────────────────────────────────────────────────────────

describe("createDebounced", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("invokes fn after delay", () => {
    const fn = vi.fn();
    const d = createDebounced(fn, 300);
    d.invoke("hello");
    expect(fn).not.toHaveBeenCalled();
    vi.advanceTimersByTime(300);
    expect(fn).toHaveBeenCalledWith("hello");
  });

  it("resets timer on repeated calls — only last invocation fires", () => {
    const fn = vi.fn();
    const d = createDebounced(fn, 300);
    d.invoke("a");
    vi.advanceTimersByTime(150);
    d.invoke("b");
    vi.advanceTimersByTime(150);
    // Not yet 300ms since "b"
    expect(fn).not.toHaveBeenCalled();
    vi.advanceTimersByTime(150);
    expect(fn).toHaveBeenCalledOnce();
    expect(fn).toHaveBeenCalledWith("b");
  });

  it("cancel prevents the fn from being called", () => {
    const fn = vi.fn();
    const d = createDebounced(fn, 300);
    d.invoke("x");
    d.cancel();
    vi.advanceTimersByTime(500);
    expect(fn).not.toHaveBeenCalled();
  });

  it("can be invoked again after cancel", () => {
    const fn = vi.fn();
    const d = createDebounced(fn, 300);
    d.invoke("first");
    d.cancel();
    d.invoke("second");
    vi.advanceTimersByTime(300);
    expect(fn).toHaveBeenCalledWith("second");
  });
});
