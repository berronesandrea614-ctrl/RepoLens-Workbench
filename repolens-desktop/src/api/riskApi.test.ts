import { describe, it, expect } from "vitest";
import { rowRiskLevel, isBlockedRow } from "./riskApi";
import type { ChangeRisk } from "./riskApi";

// ---------------------------------------------------------------------------
// Test helpers
// ---------------------------------------------------------------------------

function makeRisk(overrides: Partial<ChangeRisk> = {}): ChangeRisk {
  return {
    changeId: 1,
    category: "DESTRUCTIVE_OP",
    ruleCode: "DROP_TABLE",
    severity: "BLOCK",
    reversibility: "IRREVERSIBLE",
    evidence: "DROP TABLE users",
    acknowledged: false,
    ...overrides,
  };
}

// ---------------------------------------------------------------------------
// rowRiskLevel
// ---------------------------------------------------------------------------

describe("rowRiskLevel", () => {
  it("returns null for empty risks", () => {
    expect(rowRiskLevel([])).toBeNull();
  });

  it("returns BLOCK when any risk has severity BLOCK", () => {
    expect(rowRiskLevel([makeRisk({ severity: "BLOCK" })])).toBe("BLOCK");
  });

  it("returns WARN when only WARN risks present", () => {
    expect(
      rowRiskLevel([makeRisk({ severity: "WARN", reversibility: "REVERSIBLE" })]),
    ).toBe("WARN");
  });

  it("returns BLOCK (highest priority) when both BLOCK and WARN present", () => {
    const risks = [
      makeRisk({ severity: "WARN", reversibility: "REVERSIBLE" }),
      makeRisk({ severity: "BLOCK" }),
    ];
    expect(rowRiskLevel(risks)).toBe("BLOCK");
  });

  it("returns WARN for multiple WARN risks with no BLOCK", () => {
    const risks = [
      makeRisk({ severity: "WARN", reversibility: "REVERSIBLE" }),
      makeRisk({ severity: "WARN", ruleCode: "DELETE_NO_WHERE", reversibility: "REVERSIBLE" }),
    ];
    expect(rowRiskLevel(risks)).toBe("WARN");
  });
});

// ---------------------------------------------------------------------------
// isBlockedRow
// ---------------------------------------------------------------------------

describe("isBlockedRow", () => {
  it("returns false for empty risks", () => {
    expect(isBlockedRow([], false)).toBe(false);
  });

  it("returns true for BLOCK+IRREVERSIBLE when not acked", () => {
    const risks = [makeRisk({ severity: "BLOCK", reversibility: "IRREVERSIBLE" })];
    expect(isBlockedRow(risks, false)).toBe(true);
  });

  it("returns false when user has acked (even with BLOCK+IRREVERSIBLE)", () => {
    const risks = [makeRisk({ severity: "BLOCK", reversibility: "IRREVERSIBLE" })];
    expect(isBlockedRow(risks, true)).toBe(false);
  });

  it("returns false for WARN severity even when not acked", () => {
    const risks = [makeRisk({ severity: "WARN", reversibility: "IRREVERSIBLE" })];
    expect(isBlockedRow(risks, false)).toBe(false);
  });

  it("returns false for BLOCK+REVERSIBLE (only IRREVERSIBLE is locked)", () => {
    const risks = [makeRisk({ severity: "BLOCK", reversibility: "REVERSIBLE" })];
    expect(isBlockedRow(risks, false)).toBe(false);
  });

  it("returns true when mixed risks include BLOCK+IRREVERSIBLE and not acked", () => {
    const risks = [
      makeRisk({ severity: "WARN", reversibility: "REVERSIBLE" }),
      makeRisk({ severity: "BLOCK", reversibility: "IRREVERSIBLE" }),
    ];
    expect(isBlockedRow(risks, false)).toBe(true);
  });

  it("returns false when mixed risks include BLOCK+IRREVERSIBLE but acked", () => {
    const risks = [
      makeRisk({ severity: "WARN", reversibility: "REVERSIBLE" }),
      makeRisk({ severity: "BLOCK", reversibility: "IRREVERSIBLE" }),
    ];
    expect(isBlockedRow(risks, true)).toBe(false);
  });
});
