import { describe, it, expect } from "vitest";
import {
  getPrivacyBadgeLabel,
  getPrivacyBadgeClass,
  getPurposeLabel,
  checkIcon,
  checkClass,
  summarizeVerifyResult,
  type PrivacyVerifyResult,
  type VerifyCheckResult,
} from "./privacyApi";

// ─── getPrivacyBadgeLabel ─────────────────────────────────────────────────────

describe("getPrivacyBadgeLabel", () => {
  it("returns lock label for LOCAL_ONLY", () => {
    expect(getPrivacyBadgeLabel("LOCAL_ONLY")).toContain("本地");
    expect(getPrivacyBadgeLabel("LOCAL_ONLY")).toContain("0出网");
  });

  it("returns shield label for ALLOWLIST", () => {
    expect(getPrivacyBadgeLabel("ALLOWLIST")).toContain("白名单");
  });

  it("returns cloud label for OPEN", () => {
    expect(getPrivacyBadgeLabel("OPEN")).toContain("开放");
  });

  it("falls back to OPEN label for unknown mode", () => {
    expect(getPrivacyBadgeLabel("UNKNOWN_MODE")).toContain("开放");
  });
});

// ─── getPrivacyBadgeClass ─────────────────────────────────────────────────────

describe("getPrivacyBadgeClass", () => {
  it("returns local class for LOCAL_ONLY", () => {
    expect(getPrivacyBadgeClass("LOCAL_ONLY")).toBe("privacy-badge--local");
  });

  it("returns allowlist class for ALLOWLIST", () => {
    expect(getPrivacyBadgeClass("ALLOWLIST")).toBe("privacy-badge--allowlist");
  });

  it("returns open class for OPEN", () => {
    expect(getPrivacyBadgeClass("OPEN")).toBe("privacy-badge--open");
  });

  it("falls back to open class for unknown mode", () => {
    expect(getPrivacyBadgeClass("UNKNOWN")).toBe("privacy-badge--open");
  });
});

// ─── getPurposeLabel ──────────────────────────────────────────────────────────

describe("getPurposeLabel", () => {
  it("returns AI label for LLM", () => {
    expect(getPurposeLabel("LLM")).toBe("AI 对话");
  });

  it("returns embedding label for EMBEDDING", () => {
    expect(getPurposeLabel("EMBEDDING")).toBe("向量嵌入");
  });

  it("returns git label for GIT_CLONE", () => {
    expect(getPurposeLabel("GIT_CLONE")).toBe("Git 克隆");
  });

  it("returns dep check label for DEP_CHECK", () => {
    expect(getPurposeLabel("DEP_CHECK")).toBe("依赖体检");
  });

  it("returns raw purpose for unknown", () => {
    expect(getPurposeLabel("CUSTOM")).toBe("CUSTOM");
  });
});

// ─── checkIcon ────────────────────────────────────────────────────────────────

describe("checkIcon", () => {
  it("returns ✓ for passed check", () => {
    const c: VerifyCheckResult = { passed: true, reason: "ok" };
    expect(checkIcon(c)).toBe("✓");
  });

  it("returns ✗ for failed check", () => {
    const c: VerifyCheckResult = { passed: false, reason: "fail" };
    expect(checkIcon(c)).toBe("✗");
  });

  it("returns — for undefined", () => {
    expect(checkIcon(undefined)).toBe("—");
  });
});

// ─── checkClass ───────────────────────────────────────────────────────────────

describe("checkClass", () => {
  it("returns pass class for passed", () => {
    const c: VerifyCheckResult = { passed: true, reason: "ok" };
    expect(checkClass(c)).toBe("verify-check--pass");
  });

  it("returns fail class for failed", () => {
    const c: VerifyCheckResult = { passed: false, reason: "err" };
    expect(checkClass(c)).toBe("verify-check--fail");
  });

  it("returns unknown class for undefined", () => {
    expect(checkClass(undefined)).toBe("verify-check--unknown");
  });
});

// ─── summarizeVerifyResult ────────────────────────────────────────────────────

function makeVerifyResult(overrides: Partial<PrivacyVerifyResult> = {}): PrivacyVerifyResult {
  return {
    mode: "LOCAL_ONLY",
    llmProviderIsLocal: { passed: true, reason: "ollama" },
    baseUrlIsLoopback: { passed: true, reason: "localhost" },
    ollamaReachable: { passed: true, reason: "200" },
    recentEgressAllExternalBlocked: { passed: true, reason: "0 leaks" },
    verdict: true,
    note: "app-layer",
    checkedAt: "2026-07-05T10:00:00",
    warnings: [],
    ...overrides,
  };
}

describe("summarizeVerifyResult", () => {
  it("returns all-pass summary when verdict is true", () => {
    const r = makeVerifyResult({ verdict: true });
    const summary = summarizeVerifyResult(r);
    expect(summary).toContain("全部通过");
    expect(summary).toContain("LOCAL_ONLY");
  });

  it("mentions failed llm provider check", () => {
    const r = makeVerifyResult({
      verdict: false,
      llmProviderIsLocal: { passed: false, reason: "cloud" },
    });
    const summary = summarizeVerifyResult(r);
    expect(summary).toContain("LLM provider 非本地");
  });

  it("mentions failed baseUrl check", () => {
    const r = makeVerifyResult({
      verdict: false,
      baseUrlIsLoopback: { passed: false, reason: "non-loop" },
    });
    const summary = summarizeVerifyResult(r);
    expect(summary).toContain("baseUrl 非回环");
  });

  it("mentions ollama unreachable", () => {
    const r = makeVerifyResult({
      verdict: false,
      ollamaReachable: { passed: false, reason: "refused" },
    });
    const summary = summarizeVerifyResult(r);
    expect(summary).toContain("Ollama 不可达");
  });

  it("mentions egress leak when recentEgressAllExternalBlocked fails", () => {
    const r = makeVerifyResult({
      verdict: false,
      recentEgressAllExternalBlocked: { passed: false, reason: "leaked" },
    });
    const summary = summarizeVerifyResult(r);
    expect(summary).toContain("出网日志有外网放行");
  });

  it("mentions mode issue when all checks pass but verdict is false", () => {
    // All checks pass but verdict false (mode is OPEN)
    const r = makeVerifyResult({
      verdict: false,
      mode: "OPEN",
    });
    const summary = summarizeVerifyResult(r);
    expect(summary).toContain("LOCAL_ONLY");
  });
});
