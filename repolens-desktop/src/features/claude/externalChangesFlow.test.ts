import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import {
  filterChangedPaths,
  makeAccumulator,
  debounceAccumulate,
  cancelAccumulator,
  toRelativePaths,
  type DebounceAccumulator,
} from "./externalChangesFlow";

// ─────────────────────────────────────────────────────────────
//  filterChangedPaths
// ─────────────────────────────────────────────────────────────

describe("filterChangedPaths", () => {
  it("passes normal source files", () => {
    const paths = ["src/Main.java", "src/Util.ts", "README.md"];
    expect(filterChangedPaths(paths)).toEqual(paths);
  });

  it("filters .git files", () => {
    expect(filterChangedPaths([".git/COMMIT_EDITMSG", "src/App.ts"])).toEqual(["src/App.ts"]);
  });

  it("filters node_modules", () => {
    expect(filterChangedPaths(["node_modules/react/index.js", "src/App.ts"])).toEqual(["src/App.ts"]);
  });

  it("filters target directory", () => {
    expect(filterChangedPaths(["target/debug/foo", "src/Main.java"])).toEqual(["src/Main.java"]);
  });

  it("filters dist directory", () => {
    expect(filterChangedPaths(["dist/index.js", "src/App.ts"])).toEqual(["src/App.ts"]);
  });

  it("returns empty when all paths are filtered", () => {
    expect(filterChangedPaths([".git/HEAD", "node_modules/foo/bar.js"])).toEqual([]);
  });

  it("returns empty for empty input", () => {
    expect(filterChangedPaths([])).toEqual([]);
  });
});

// ─────────────────────────────────────────────────────────────
//  toRelativePaths
// ─────────────────────────────────────────────────────────────

describe("toRelativePaths", () => {
  const realDir = "/home/user/myproject";

  it("converts absolute paths under realDir to relative", () => {
    const result = toRelativePaths(
      ["/home/user/myproject/src/App.ts", "/home/user/myproject/README.md"],
      realDir,
    );
    expect(result).toEqual(["src/App.ts", "README.md"]);
  });

  it("drops paths outside realDir", () => {
    const result = toRelativePaths(
      ["/home/other/project/src/App.ts", "/home/user/myproject/src/Foo.java"],
      realDir,
    );
    expect(result).toEqual(["src/Foo.java"]);
  });

  it("handles realDir with trailing slash", () => {
    const result = toRelativePaths(
      ["/home/user/myproject/src/App.ts"],
      "/home/user/myproject/",
    );
    expect(result).toEqual(["src/App.ts"]);
  });

  it("returns empty for empty input", () => {
    expect(toRelativePaths([], realDir)).toEqual([]);
  });

  it("handles Windows-style backslash paths", () => {
    const result = toRelativePaths(
      ["C:\\Users\\foo\\project\\src\\Main.java"],
      "C:\\Users\\foo\\project",
    );
    expect(result).toEqual(["src/Main.java"]);
  });
});

// ─────────────────────────────────────────────────────────────
//  debounceAccumulate / cancelAccumulator
// ─────────────────────────────────────────────────────────────

describe("debounceAccumulate", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("calls flush after delay with accumulated paths", () => {
    const acc: DebounceAccumulator = makeAccumulator();
    const flush = vi.fn();

    debounceAccumulate(acc, ["src/A.ts"], 200, flush);
    expect(flush).not.toHaveBeenCalled();

    vi.advanceTimersByTime(200);
    expect(flush).toHaveBeenCalledWith(["src/A.ts"]);
  });

  it("accumulates paths from multiple calls within the window", () => {
    const acc: DebounceAccumulator = makeAccumulator();
    const flush = vi.fn();

    debounceAccumulate(acc, ["src/A.ts"], 200, flush);
    debounceAccumulate(acc, ["src/B.ts"], 200, flush);
    debounceAccumulate(acc, ["src/C.ts"], 200, flush);

    vi.advanceTimersByTime(200);
    // All three paths flushed in one call, deduplicated.
    expect(flush).toHaveBeenCalledOnce();
    const flushedPaths = flush.mock.calls[0][0] as string[];
    expect(flushedPaths.sort()).toEqual(["src/A.ts", "src/B.ts", "src/C.ts"]);
  });

  it("deduplicates repeated paths", () => {
    const acc: DebounceAccumulator = makeAccumulator();
    const flush = vi.fn();

    debounceAccumulate(acc, ["src/A.ts", "src/A.ts"], 200, flush);
    debounceAccumulate(acc, ["src/A.ts"], 200, flush);

    vi.advanceTimersByTime(200);
    expect(flush).toHaveBeenCalledWith(["src/A.ts"]);
  });

  it("resets after flush — next batch starts fresh", () => {
    const acc: DebounceAccumulator = makeAccumulator();
    const flush = vi.fn();

    debounceAccumulate(acc, ["src/A.ts"], 200, flush);
    vi.advanceTimersByTime(200);
    expect(flush).toHaveBeenCalledOnce();

    // Second batch
    debounceAccumulate(acc, ["src/B.ts"], 200, flush);
    vi.advanceTimersByTime(200);
    expect(flush).toHaveBeenCalledTimes(2);
    expect(flush.mock.calls[1][0]).toEqual(["src/B.ts"]);
  });

  it("cancels the previous timer on each new call (only one flush for rapid changes)", () => {
    const acc: DebounceAccumulator = makeAccumulator();
    const flush = vi.fn();

    debounceAccumulate(acc, ["src/A.ts"], 200, flush);
    vi.advanceTimersByTime(100); // halfway through
    debounceAccumulate(acc, ["src/B.ts"], 200, flush); // resets timer
    vi.advanceTimersByTime(100); // not yet 200ms since last call
    expect(flush).not.toHaveBeenCalled();

    vi.advanceTimersByTime(100); // now 200ms since last call
    expect(flush).toHaveBeenCalledOnce();
  });

  it("ignores paths that should be filtered (e.g. .git)", () => {
    const acc: DebounceAccumulator = makeAccumulator();
    const flush = vi.fn();

    debounceAccumulate(acc, [".git/HEAD", "node_modules/foo"], 200, flush);
    vi.advanceTimersByTime(200);
    // All filtered — flush should not be called since filtered result is empty.
    expect(flush).not.toHaveBeenCalled();
  });

  it("mixed filtered and non-filtered — only unfiltered paths flushed", () => {
    const acc: DebounceAccumulator = makeAccumulator();
    const flush = vi.fn();

    debounceAccumulate(acc, [".git/HEAD", "src/App.ts"], 200, flush);
    vi.advanceTimersByTime(200);
    expect(flush).toHaveBeenCalledWith(["src/App.ts"]);
  });
});

describe("cancelAccumulator", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("cancels pending timer — flush is never called", () => {
    const acc: DebounceAccumulator = makeAccumulator();
    const flush = vi.fn();

    debounceAccumulate(acc, ["src/A.ts"], 200, flush);
    cancelAccumulator(acc);

    vi.advanceTimersByTime(500);
    expect(flush).not.toHaveBeenCalled();
  });

  it("clears pending paths", () => {
    const acc: DebounceAccumulator = makeAccumulator();
    const flush = vi.fn();

    debounceAccumulate(acc, ["src/A.ts"], 200, flush);
    cancelAccumulator(acc);

    expect(acc.pending.size).toBe(0);
    expect(acc.timer).toBeNull();
  });
});
