import { describe, it, expect } from "vitest";
import {
  getPlanItemIcon,
  getClassificationBadge,
  getCheckIcon,
  getTrustBadge,
  shouldShowReconciliation,
  buildReconChips,
  getSilentAdds,
  getOverScopes,
  fileBaseName,
  hasRedChecks,
  getBlockViolations,
  getConstraintIcon,
} from "./reconciliationUtils";
import type { ReconciliationVO, SelfReportCheck, ConstraintViolation } from "./reconciliationTypes";

// ── Helpers ─────────────────────────────────────────────────────────────────

function makeVO(overrides: Partial<ReconciliationVO> = {}): ReconciliationVO {
  return {
    planned: true,
    degrade: false,
    summary: {
      coverage: 1.0,
      fidelity: 1.0,
      offPlanCount: 0,
      violationCount: 0,
      trustFlag: "OK",
      humanLine: "ok",
    },
    items: [],
    offPlan: [],
    selfReport: { claimedSuccess: false, claimedVerified: false, trustFlag: "OK", checks: [] },
    ...overrides,
  };
}

// ── getPlanItemIcon ──────────────────────────────────────────────────────────

describe("getPlanItemIcon", () => {
  it("LANDED returns check icon", () => {
    expect(getPlanItemIcon("LANDED").icon).toBe("✅");
  });
  it("PARTIAL returns yellow icon", () => {
    expect(getPlanItemIcon("PARTIAL").icon).toBe("🟡");
  });
  it("MISSING_SILENT returns flag icon", () => {
    expect(getPlanItemIcon("MISSING_SILENT").icon).toBe("🚩");
  });
  it("MISSING_ATTEMPTED returns orange icon", () => {
    expect(getPlanItemIcon("MISSING_ATTEMPTED").icon).toBe("🟠");
  });
  it("unknown returns question icon", () => {
    expect(getPlanItemIcon("UNKNOWN").icon).toBe("❓");
  });
});

// ── getClassificationBadge ───────────────────────────────────────────────────

describe("getClassificationBadge", () => {
  it("SILENT_ADD is severe", () => {
    const badge = getClassificationBadge("SILENT_ADD");
    expect(badge.severe).toBe(true);
    expect(badge.label).toContain("静默新增");
  });
  it("OVER_SCOPE is not severe", () => {
    const badge = getClassificationBadge("OVER_SCOPE");
    expect(badge.severe).toBe(false);
    expect(badge.label).toContain("超范围");
  });
});

// ── getCheckIcon ─────────────────────────────────────────────────────────────

describe("getCheckIcon", () => {
  it("RED returns red circle", () => {
    const check: SelfReportCheck = { type: "FABRICATED_VERIFICATION", severity: "RED", detail: "x" };
    expect(getCheckIcon(check)).toBe("🔴");
  });
  it("ORANGE returns orange circle", () => {
    const check: SelfReportCheck = { type: "NO_OP_SUCCESS", severity: "ORANGE", detail: "x" };
    expect(getCheckIcon(check)).toBe("🟠");
  });
});

// ── getTrustBadge ────────────────────────────────────────────────────────────

describe("getTrustBadge", () => {
  it("FABRICATED shows red label", () => {
    expect(getTrustBadge("FABRICATED").label).toContain("存疑");
  });
  it("OK shows check label", () => {
    expect(getTrustBadge("OK").label).toContain("✅");
  });
  it("SUSPECT shows orange label", () => {
    expect(getTrustBadge("SUSPECT").label).toContain("部分");
  });
});

// ── shouldShowReconciliation ─────────────────────────────────────────────────

describe("shouldShowReconciliation", () => {
  it("returns false for null", () => {
    expect(shouldShowReconciliation(null)).toBe(false);
  });
  it("returns true for planned VO", () => {
    expect(shouldShowReconciliation(makeVO({ planned: true }))).toBe(true);
  });
  it("returns true when offPlan has items even without plan", () => {
    expect(shouldShowReconciliation(makeVO({
      planned: false,
      offPlan: [{ filePath: "Foo.java", classification: "SILENT_ADD" }],
    }))).toBe(true);
  });
  it("returns false when degrade and no items and no plan", () => {
    const vo: ReconciliationVO = {
      planned: false, degrade: true,
      items: [], offPlan: [], selfReport: undefined,
    };
    expect(shouldShowReconciliation(vo)).toBe(false);
  });
});

// ── buildReconChips ──────────────────────────────────────────────────────────

describe("buildReconChips", () => {
  it("includes green coverage chip for 100%", () => {
    const chips = buildReconChips(makeVO());
    expect(chips.some((c) => c.text.includes("100") && c.cls === "g")).toBe(true);
  });
  it("includes red off-plan chip when offPlanCount > 0", () => {
    const chips = buildReconChips(makeVO({ summary: {
      coverage: 0.8, fidelity: 0.75, offPlanCount: 2, violationCount: 0, trustFlag: "FABRICATED",
    }}));
    expect(chips.some((c) => c.cls === "r" && c.text.includes("计划外"))).toBe(true);
  });
  it("includes trust OK chip", () => {
    const chips = buildReconChips(makeVO());
    expect(chips.some((c) => c.text.includes("✅"))).toBe(true);
  });
});

// ── getSilentAdds / getOverScopes ─────────────────────────────────────────────

describe("getSilentAdds / getOverScopes", () => {
  const offPlan = [
    { filePath: "SecurityConfig.java", classification: "SILENT_ADD" },
    { filePath: "CaptchaHelper.java",  classification: "OVER_SCOPE" },
    { filePath: "LoginService.java",   classification: "IN_PLAN" },
  ];
  it("getSilentAdds returns only SILENT_ADD", () => {
    expect(getSilentAdds(offPlan)).toHaveLength(1);
    expect(getSilentAdds(offPlan)[0].filePath).toBe("SecurityConfig.java");
  });
  it("getOverScopes returns only OVER_SCOPE", () => {
    expect(getOverScopes(offPlan)).toHaveLength(1);
    expect(getOverScopes(offPlan)[0].filePath).toBe("CaptchaHelper.java");
  });
});

// ── fileBaseName ──────────────────────────────────────────────────────────────

describe("fileBaseName", () => {
  it("extracts file name from path", () => {
    expect(fileBaseName("src/main/java/SecurityConfig.java")).toBe("SecurityConfig.java");
  });
  it("returns the name itself when no slash", () => {
    expect(fileBaseName("Foo.java")).toBe("Foo.java");
  });
});

// ── hasRedChecks ──────────────────────────────────────────────────────────────

describe("hasRedChecks", () => {
  it("returns true when any RED check exists", () => {
    const checks: SelfReportCheck[] = [
      { type: "FABRICATED_VERIFICATION", severity: "RED", detail: "x" },
    ];
    expect(hasRedChecks(checks)).toBe(true);
  });
  it("returns false when only ORANGE checks", () => {
    const checks: SelfReportCheck[] = [
      { type: "NO_OP_SUCCESS", severity: "ORANGE", detail: "x" },
    ];
    expect(hasRedChecks(checks)).toBe(false);
  });
  it("returns false for empty array", () => {
    expect(hasRedChecks([])).toBe(false);
  });
  it("returns false for undefined", () => {
    expect(hasRedChecks(undefined)).toBe(false);
  });
});

// ── buildReconChips — violation chip ─────────────────────────────────────────

describe("buildReconChips with violation count", () => {
  it("adds red violation chip when violationCount > 0", () => {
    const chips = buildReconChips(makeVO({
      summary: { coverage: 1.0, fidelity: 1.0, offPlanCount: 0, violationCount: 2, trustFlag: "OK" },
    }));
    const chip = chips.find((c) => c.text.includes("约束违规"));
    expect(chip).toBeDefined();
    expect(chip?.cls).toBe("r");
    expect(chip?.text).toContain("2");
  });
  it("does not add violation chip when violationCount is 0", () => {
    const chips = buildReconChips(makeVO());
    expect(chips.every((c) => !c.text.includes("约束违规"))).toBe(true);
  });
});

// ── getBlockViolations ────────────────────────────────────────────────────────

describe("getBlockViolations", () => {
  const violations: ConstraintViolation[] = [
    { ruleType: "NO_NEW_DEP", rawText: "禁止新增依赖", matchedFiles: ["pom.xml"], severity: "BLOCK" },
    { ruleType: "KEEP_SCOPE", rawText: "范围说明", matchedFiles: [], severity: "WARN" },
    { ruleType: "PATH_FORBIDDEN", rawText: "禁止改 config/", matchedFiles: ["config/Foo.java"], severity: "BLOCK" },
  ];
  it("returns only BLOCK severity violations", () => {
    const result = getBlockViolations(violations);
    expect(result).toHaveLength(2);
    expect(result.every((v) => v.severity === "BLOCK")).toBe(true);
  });
  it("returns empty array for undefined input", () => {
    expect(getBlockViolations(undefined)).toHaveLength(0);
  });
  it("returns empty array when no BLOCK violations", () => {
    const warnOnly: ConstraintViolation[] = [
      { ruleType: "KEEP_SCOPE", rawText: "x", matchedFiles: [], severity: "WARN" },
    ];
    expect(getBlockViolations(warnOnly)).toHaveLength(0);
  });
});

// ── getConstraintIcon ─────────────────────────────────────────────────────────

describe("getConstraintIcon", () => {
  it("PATH_FORBIDDEN returns 🚫", () => {
    expect(getConstraintIcon("PATH_FORBIDDEN")).toBe("🚫");
  });
  it("NO_NEW_DEP returns 📦", () => {
    expect(getConstraintIcon("NO_NEW_DEP")).toBe("📦");
  });
  it("MUST_VERIFY returns 🧪", () => {
    expect(getConstraintIcon("MUST_VERIFY")).toBe("🧪");
  });
  it("KEEP_SCOPE returns 📐", () => {
    expect(getConstraintIcon("KEEP_SCOPE")).toBe("📐");
  });
  it("unknown returns ⚠️", () => {
    expect(getConstraintIcon("UNKNOWN")).toBe("⚠️");
  });
});
