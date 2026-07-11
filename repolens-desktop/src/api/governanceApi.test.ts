import { describe, expect, it } from "vitest";
import { severityColor, normalizeSensitiveSignal, getSignalNorm } from "./governanceApi";
import type { SensitiveFile } from "./governanceApi";

describe("severityColor", () => {
  it("returns red palette for BLOCK", () => {
    const c = severityColor("BLOCK");
    expect(c.text).toBe("#e74c3c");
    expect(c.bg).toContain("231");
  });

  it("returns amber palette for WARN", () => {
    const c = severityColor("WARN");
    expect(c.text).toBe("#f39c12");
    expect(c.bg).toContain("243");
  });

  it("returns gray palette for INFO", () => {
    const c = severityColor("INFO");
    expect(c.text).toBe("#8b949e");
    expect(c.bg).toContain("139");
  });
});

describe("normalizeSensitiveSignal", () => {
  it("normalizes fanIn by 20 max", () => {
    expect(normalizeSensitiveSignal("fanIn", 0)).toBe(0);
    expect(normalizeSensitiveSignal("fanIn", 10)).toBeCloseTo(0.5);
    expect(normalizeSensitiveSignal("fanIn", 20)).toBe(1);
    expect(normalizeSensitiveSignal("fanIn", 40)).toBe(1); // clamped
  });

  it("normalizes churn by 50 max", () => {
    expect(normalizeSensitiveSignal("churn", 0)).toBe(0);
    expect(normalizeSensitiveSignal("churn", 25)).toBeCloseTo(0.5);
    expect(normalizeSensitiveSignal("churn", 50)).toBe(1);
    expect(normalizeSensitiveSignal("churn", 100)).toBe(1); // clamped
  });

  it("clamps aiRatio to [0,1]", () => {
    expect(normalizeSensitiveSignal("aiRatio", 0)).toBe(0);
    expect(normalizeSensitiveSignal("aiRatio", 0.75)).toBeCloseTo(0.75);
    expect(normalizeSensitiveSignal("aiRatio", 1)).toBe(1);
    expect(normalizeSensitiveSignal("aiRatio", -0.5)).toBe(0);
    expect(normalizeSensitiveSignal("aiRatio", 1.5)).toBe(1);
  });

  it("treats constraintHit as boolean (>0 → 1, else 0)", () => {
    expect(normalizeSensitiveSignal("constraintHit", 0)).toBe(0);
    expect(normalizeSensitiveSignal("constraintHit", 1)).toBe(1);
    expect(normalizeSensitiveSignal("constraintHit", 0.5)).toBe(1);
  });

  it("clamps unknown keys to [0,1]", () => {
    expect(normalizeSensitiveSignal("unknown", -1)).toBe(0);
    expect(normalizeSensitiveSignal("unknown", 0.4)).toBeCloseTo(0.4);
    expect(normalizeSensitiveSignal("unknown", 2)).toBe(1);
  });
});

// ─── Helper to build a minimal SensitiveFile fixture ──────────────────────────
function makeFile(overrides: Partial<SensitiveFile> = {}): SensitiveFile {
  return {
    id: 1,
    repoId: 42,
    filePath: "src/Foo.java",
    fanIn: 0,
    churn: 0,
    aiRatio: 0,
    constraintHit: false,
    finalScore: 0,
    severity: "INFO",
    reason: null,
    signals: null,
    rankNo: 1,
    ...overrides,
  };
}

describe("getSignalNorm — signals map path (no double-normalization)", () => {
  it("signals.fanIn=1.0 returns 1.0, not 0.05 (was double-normalized before fix)", () => {
    const file = makeFile({ signals: { fanIn: 1.0 } });
    expect(getSignalNorm(file, "fanIn")).toBe(1.0);
  });

  it("signals.fanIn=0.5 returns 0.5 (already normalized)", () => {
    const file = makeFile({ signals: { fanIn: 0.5 } });
    expect(getSignalNorm(file, "fanIn")).toBeCloseTo(0.5);
  });

  it("signals.churn=0.8 returns 0.8, not 0.016 (was churn/50 again)", () => {
    const file = makeFile({ signals: { churn: 0.8 } });
    expect(getSignalNorm(file, "churn")).toBeCloseTo(0.8);
  });

  it("clamps out-of-range signals value to [0,1]", () => {
    const file = makeFile({ signals: { fanIn: 1.5 } });
    expect(getSignalNorm(file, "fanIn")).toBe(1.0);
  });
});

describe("getSignalNorm — directField fallback path (signals null/missing key)", () => {
  it("falls back to fanIn raw count and normalizes (fanIn=10 → 0.5)", () => {
    const file = makeFile({ fanIn: 10, signals: null });
    expect(getSignalNorm(file, "fanIn")).toBeCloseTo(0.5);
  });

  it("falls back to churn raw count and normalizes (churn=25 → 0.5)", () => {
    const file = makeFile({ churn: 25, signals: null });
    expect(getSignalNorm(file, "churn")).toBeCloseTo(0.5);
  });

  it("falls back to aiRatio field (aiRatio=0.75 → 0.75)", () => {
    const file = makeFile({ aiRatio: 0.75, signals: null });
    expect(getSignalNorm(file, "aiRatio")).toBeCloseTo(0.75);
  });

  it("falls back to constraintHit boolean (true → 1)", () => {
    const file = makeFile({ constraintHit: true, signals: null });
    expect(getSignalNorm(file, "constraintHit")).toBe(1);
  });
});
