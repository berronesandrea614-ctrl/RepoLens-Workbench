import { describe, expect, it } from "vitest";
import type { AgentLane, ReviewItem } from "../../api/missionApi";
import {
  laneNeedsHighlight,
  deviationNeedsAlert,
  formatCoverage,
  formatStatus,
  statusColor,
  severityBadgeColor,
  isInterrupt,
} from "./missionHelpers";

// ─── Fixtures ─────────────────────────────────────────────────────────────────

function makeLane(overrides: Partial<AgentLane> = {}): AgentLane {
  return {
    laneId: 1,
    engine: "NATIVE",
    status: "COMPLETED",
    claimedSuccess: true,
    claimedVerified: true,
    planLine: "实现登录模块",
    changesLine: "改 3 个文件",
    changedFileCount: 3,
    deviation: null,
    debtCount: 0,
    risk: { blockCount: 0, warnCount: 0, hasIrreversibleBlock: false },
    needsAttention: false,
    degraded: false,
    ...overrides,
  };
}

function makeReviewItem(overrides: Partial<ReviewItem> = {}): ReviewItem {
  return {
    changeId: 1,
    kind: "DEPENDENCY_INJECT",
    reversibility: "REVERSIBLE",
    severity: "WARN",
    interrupt: false,
    filePath: "src/Main.java",
    evidence: "some evidence",
    ...overrides,
  };
}

// ─── laneNeedsHighlight ───────────────────────────────────────────────────────

describe("laneNeedsHighlight", () => {
  it("returns false when needsAttention is false", () => {
    expect(laneNeedsHighlight(makeLane({ needsAttention: false }))).toBe(false);
  });

  it("returns true when needsAttention is true", () => {
    expect(laneNeedsHighlight(makeLane({ needsAttention: true }))).toBe(true);
  });
});

// ─── deviationNeedsAlert ──────────────────────────────────────────────────────

describe("deviationNeedsAlert", () => {
  it("returns false for OK", () => {
    expect(deviationNeedsAlert("OK")).toBe(false);
  });

  it("returns true for STALE", () => {
    expect(deviationNeedsAlert("STALE")).toBe(true);
  });

  it("returns true for SUSPICIOUS", () => {
    expect(deviationNeedsAlert("SUSPICIOUS")).toBe(true);
  });

  it("returns false for undefined", () => {
    expect(deviationNeedsAlert(undefined)).toBe(false);
  });
});

// ─── formatCoverage ───────────────────────────────────────────────────────────

describe("formatCoverage", () => {
  it("appends percent sign", () => {
    expect(formatCoverage(75)).toBe("75%");
    expect(formatCoverage(0)).toBe("0%");
    expect(formatCoverage(100)).toBe("100%");
  });
});

// ─── formatStatus ─────────────────────────────────────────────────────────────

describe("formatStatus", () => {
  it("maps known statuses to Chinese labels", () => {
    expect(formatStatus("COMPLETED")).toBe("已完成");
    expect(formatStatus("RUNNING")).toBe("运行中");
    expect(formatStatus("FAILED")).toBe("失败");
    expect(formatStatus("PENDING")).toBe("等待中");
    expect(formatStatus("CANCELLED")).toBe("已取消");
  });

  it("returns unknown status as-is", () => {
    expect(formatStatus("UNKNOWN")).toBe("UNKNOWN");
  });
});

// ─── statusColor ─────────────────────────────────────────────────────────────

describe("statusColor", () => {
  it("returns green for COMPLETED", () => {
    expect(statusColor("COMPLETED")).toBe("#27ae60");
  });

  it("returns blue for RUNNING", () => {
    expect(statusColor("RUNNING")).toBe("#4daafc");
  });

  it("returns red for FAILED", () => {
    expect(statusColor("FAILED")).toBe("#e74c3c");
  });

  it("returns gray for PENDING and CANCELLED", () => {
    expect(statusColor("PENDING")).toBe("#8b949e");
    expect(statusColor("CANCELLED")).toBe("#8b949e");
  });

  it("returns gray for unknown status", () => {
    expect(statusColor("WHATEVER")).toBe("#8b949e");
  });
});

// ─── severityBadgeColor ──────────────────────────────────────────────────────

describe("severityBadgeColor", () => {
  it("returns red palette for BLOCK", () => {
    const c = severityBadgeColor("BLOCK");
    expect(c.text).toBe("#e74c3c");
    expect(c.bg).toContain("231");
  });

  it("returns amber palette for WARN", () => {
    const c = severityBadgeColor("WARN");
    expect(c.text).toBe("#f39c12");
    expect(c.bg).toContain("243");
  });

  it("returns gray palette for INFO", () => {
    const c = severityBadgeColor("INFO");
    expect(c.text).toBe("#8b949e");
    expect(c.bg).toContain("139");
  });

  it("returns gray palette for unknown severity", () => {
    const c = severityBadgeColor("OTHER");
    expect(c.text).toBe("#8b949e");
  });
});

// ─── isInterrupt ─────────────────────────────────────────────────────────────

describe("isInterrupt", () => {
  it("returns false when interrupt is false", () => {
    expect(isInterrupt(makeReviewItem({ interrupt: false }))).toBe(false);
  });

  it("returns true when interrupt is true", () => {
    expect(isInterrupt(makeReviewItem({ interrupt: true }))).toBe(true);
  });
});
