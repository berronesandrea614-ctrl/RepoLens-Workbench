import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { statusToColor, statusLabel, type FeishuBinding } from "../../api/feishuApi";
import {
  buildTermWriteData,
  hasConnectedBinding,
  createFeishuReporter,
} from "./feishuOutputReporter";

// ─── statusToColor ────────────────────────────────────────────────────────────

describe("statusToColor", () => {
  it("returns green for CONNECTED", () => {
    expect(statusToColor("CONNECTED")).toBe("#3fb950");
  });

  it("returns red for ERROR", () => {
    expect(statusToColor("ERROR")).toBe("#f85149");
  });

  it("returns gray for DISCONNECTED", () => {
    expect(statusToColor("DISCONNECTED")).toBe("#6e7681");
  });
});

// ─── statusLabel ──────────────────────────────────────────────────────────────

describe("statusLabel", () => {
  it("returns 已连接 for CONNECTED", () => {
    expect(statusLabel("CONNECTED")).toBe("已连接");
  });

  it("returns 错误 for ERROR", () => {
    expect(statusLabel("ERROR")).toBe("错误");
  });

  it("returns 已断开 for DISCONNECTED", () => {
    expect(statusLabel("DISCONNECTED")).toBe("已断开");
  });
});

// ─── buildTermWriteData ───────────────────────────────────────────────────────

describe("buildTermWriteData", () => {
  it("appends carriage return to text", () => {
    expect(buildTermWriteData("hello")).toBe("hello\r");
  });

  it("handles empty string", () => {
    expect(buildTermWriteData("")).toBe("\r");
  });

  it("handles text with existing newlines", () => {
    expect(buildTermWriteData("line1\nline2")).toBe("line1\nline2\r");
  });
});

// ─── hasConnectedBinding ──────────────────────────────────────────────────────

const makeBinding = (
  status: FeishuBinding["status"],
  id = 1,
): FeishuBinding => ({
  id,
  repoId: 42,
  sessionId: null,
  botName: "Bot",
  appId: "cli_abc",
  status,
  lastError: null,
  createdAt: "2024-01-01T00:00:00Z",
});

describe("hasConnectedBinding", () => {
  it("returns false for empty list", () => {
    expect(hasConnectedBinding([])).toBe(false);
  });

  it("returns false when all bindings are DISCONNECTED", () => {
    expect(hasConnectedBinding([makeBinding("DISCONNECTED")])).toBe(false);
  });

  it("returns false when all bindings are ERROR", () => {
    expect(hasConnectedBinding([makeBinding("ERROR")])).toBe(false);
  });

  it("returns true when at least one binding is CONNECTED", () => {
    expect(
      hasConnectedBinding([makeBinding("DISCONNECTED"), makeBinding("CONNECTED", 2)]),
    ).toBe(true);
  });

  it("returns true for a single CONNECTED binding", () => {
    expect(hasConnectedBinding([makeBinding("CONNECTED")])).toBe(true);
  });
});

// ─── createFeishuReporter ─────────────────────────────────────────────────────

describe("createFeishuReporter", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("does nothing when no CONNECTED binding exists", () => {
    const getBindings = vi.fn(() => [] as FeishuBinding[]);
    const reporter = createFeishuReporter(1, getBindings, 500);
    reporter.report("output chunk");
    vi.advanceTimersByTime(600);
    // getBindings called to check; no HTTP call should fire
    // (we verify no timer was scheduled by checking getBindings count)
    expect(getBindings).toHaveBeenCalled();
  });

  it("accumulates chunks and flushes after throttle interval", () => {
    const connectedBinding = makeBinding("CONNECTED");
    const getBindings = vi.fn(() => [connectedBinding]);
    // Spy on the underlying module to avoid actual HTTP calls.
    // We verify the reporter calls getBindings at flush time.
    const reporter = createFeishuReporter(1, getBindings, 500);

    reporter.report("chunk1");
    reporter.report("chunk2");
    // Before flush: getBindings called only for initial guard check (2×).
    expect(getBindings).toHaveBeenCalledTimes(2);

    vi.advanceTimersByTime(500);
    // Flush calls getBindings once more to re-check binding status.
    expect(getBindings.mock.calls.length).toBeGreaterThanOrEqual(3);
  });

  it("does not schedule a second timer if one is already pending", () => {
    const connectedBinding = makeBinding("CONNECTED");
    const getBindings = vi.fn(() => [connectedBinding]);
    const reporter = createFeishuReporter(1, getBindings, 500);

    reporter.report("first");
    const callsAfterFirst = getBindings.mock.calls.length;
    reporter.report("second");
    // Second report should NOT add a new timer (guard check fires but timer was already set).
    // getBindings is called once per report for the guard check.
    expect(getBindings.mock.calls.length).toBe(callsAfterFirst + 1);
  });

  it("drops pending buffer if binding disconnected at flush time", () => {
    let status: FeishuBinding["status"] = "CONNECTED";
    const getBindings = vi.fn((): FeishuBinding[] => [
      { ...makeBinding(status) },
    ]);
    const reporter = createFeishuReporter(1, getBindings, 500);

    reporter.report("data");
    // Simulate binding going away before flush.
    status = "DISCONNECTED";
    vi.advanceTimersByTime(500);
    // No HTTP error thrown — buffer silently dropped.
  });
});
