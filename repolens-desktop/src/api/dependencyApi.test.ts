import { describe, it, expect } from "vitest";
import {
  parseTyposquatDetail,
  getVerdictLabel,
  getVerdictSeverity,
} from "./dependencyApi";

// ---------------------------------------------------------------------------
// parseTyposquatDetail
// ---------------------------------------------------------------------------

describe("parseTyposquatDetail", () => {
  it("returns suggestion and distance from valid JSON", () => {
    const result = parseTyposquatDetail('{"suggestion":"requests","distance":1}');
    expect(result.suggestion).toBe("requests");
    expect(result.distance).toBe(1);
  });

  it("returns empty object for null", () => {
    expect(parseTyposquatDetail(null)).toEqual({});
  });

  it("returns empty object for empty string", () => {
    expect(parseTyposquatDetail("")).toEqual({});
  });

  it("returns empty object for invalid JSON", () => {
    expect(parseTyposquatDetail("NOT JSON")).toEqual({});
  });

  it("returns partial object when only suggestion present", () => {
    const result = parseTyposquatDetail('{"suggestion":"lodash"}');
    expect(result.suggestion).toBe("lodash");
    expect(result.distance).toBeUndefined();
  });
});

// ---------------------------------------------------------------------------
// getVerdictLabel
// ---------------------------------------------------------------------------

describe("getVerdictLabel", () => {
  it("returns malicious label for MALICIOUS", () => {
    expect(getVerdictLabel("MALICIOUS")).toBe("☠ 恶意");
  });

  it("returns warning label for TYPOSQUAT", () => {
    expect(getVerdictLabel("TYPOSQUAT")).toBe("⚠ 疑似抢注");
  });

  it("returns not-found label for NOT_FOUND", () => {
    expect(getVerdictLabel("NOT_FOUND")).toBe("⛔ 不存在");
  });

  it("returns vulnerable label for VULNERABLE", () => {
    expect(getVerdictLabel("VULNERABLE")).toBe("⚠ 有漏洞");
  });

  it("returns ok label for OK", () => {
    expect(getVerdictLabel("OK")).toBe("✅ 安全");
  });

  it("returns unknown label for UNKNOWN", () => {
    expect(getVerdictLabel("UNKNOWN")).toBe("? 未知");
  });
});

// ---------------------------------------------------------------------------
// getVerdictSeverity
// ---------------------------------------------------------------------------

describe("getVerdictSeverity", () => {
  it("maps MALICIOUS to critical", () => {
    expect(getVerdictSeverity("MALICIOUS")).toBe("critical");
  });

  it("maps TYPOSQUAT to typosquat", () => {
    expect(getVerdictSeverity("TYPOSQUAT")).toBe("typosquat");
  });

  it("maps NOT_FOUND to notfound", () => {
    expect(getVerdictSeverity("NOT_FOUND")).toBe("notfound");
  });

  it("maps VULNERABLE to warning", () => {
    expect(getVerdictSeverity("VULNERABLE")).toBe("warning");
  });

  it("maps OK to ok", () => {
    expect(getVerdictSeverity("OK")).toBe("ok");
  });

  it("maps UNKNOWN to unknown", () => {
    expect(getVerdictSeverity("UNKNOWN")).toBe("unknown");
  });
});
