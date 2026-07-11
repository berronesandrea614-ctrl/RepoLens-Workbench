import { describe, it, expect } from "vitest";
import {
  formatDecision,
  getDecisionClass,
  formatPromptFingerprint,
  formatDecidedAt,
} from "./provenanceApi";

// ─── formatDecision ───────────────────────────────────────────────────────────

describe("formatDecision", () => {
  it("maps APPROVED to 已批准", () => {
    expect(formatDecision("APPROVED")).toBe("已批准");
  });

  it("maps REJECTED to 已拒绝", () => {
    expect(formatDecision("REJECTED")).toBe("已拒绝");
  });

  it("maps REVERTED to 已回滚", () => {
    expect(formatDecision("REVERTED")).toBe("已回滚");
  });

  it("case-insensitive: approved maps same as APPROVED", () => {
    expect(formatDecision("approved")).toBe("已批准");
  });

  it("unknown values returned as-is", () => {
    expect(formatDecision("CUSTOM")).toBe("CUSTOM");
  });

  it("null/undefined returns 未知", () => {
    expect(formatDecision(null as unknown as string)).toBe("未知");
    expect(formatDecision(undefined as unknown as string)).toBe("未知");
  });
});

// ─── getDecisionClass ─────────────────────────────────────────────────────────

describe("getDecisionClass", () => {
  it("returns decision-approved for APPROVED", () => {
    expect(getDecisionClass("APPROVED")).toBe("decision-approved");
  });

  it("returns decision-rejected for REJECTED", () => {
    expect(getDecisionClass("REJECTED")).toBe("decision-rejected");
  });

  it("returns decision-reverted for REVERTED", () => {
    expect(getDecisionClass("REVERTED")).toBe("decision-reverted");
  });

  it("returns decision-unknown for unknown value", () => {
    expect(getDecisionClass("OTHER")).toBe("decision-unknown");
  });

  it("case-insensitive: rejected maps to decision-rejected", () => {
    expect(getDecisionClass("rejected")).toBe("decision-rejected");
  });
});

// ─── formatPromptFingerprint ──────────────────────────────────────────────────

describe("formatPromptFingerprint", () => {
  it("returns first 8 chars for full hash", () => {
    expect(formatPromptFingerprint("aabbccddee112233")).toBe("aabbccdd");
  });

  it("returns null placeholder for null", () => {
    expect(formatPromptFingerprint(null)).toBe("未知(历史变更)");
  });

  it("returns null placeholder for undefined", () => {
    expect(formatPromptFingerprint(undefined)).toBe("未知(历史变更)");
  });

  it("returns — for empty string", () => {
    expect(formatPromptFingerprint("")).toBe("—");
  });

  it("returns entire string if shorter than 8 chars", () => {
    expect(formatPromptFingerprint("abc")).toBe("abc");
  });
});

// ─── formatDecidedAt ─────────────────────────────────────────────────────────

describe("formatDecidedAt", () => {
  it("formats ISO datetime to yyyy-MM-dd HH:mm", () => {
    // 2026-07-05T10:30:00 → "2026-07-05 10:30"
    const result = formatDecidedAt("2026-07-05T10:30:00");
    expect(result).toMatch(/^2026-07-05 \d{2}:\d{2}$/);
  });

  it("returns — for null", () => {
    expect(formatDecidedAt(null)).toBe("—");
  });

  it("returns — for undefined", () => {
    expect(formatDecidedAt(undefined)).toBe("—");
  });

  it("returns — for empty string", () => {
    expect(formatDecidedAt("")).toBe("—");
  });

  it("contains correct date parts for known timestamp", () => {
    const result = formatDecidedAt("2026-01-15T08:05:00");
    expect(result).toContain("2026");
    expect(result).toContain("01");
    expect(result).toContain("15");
  });
});
