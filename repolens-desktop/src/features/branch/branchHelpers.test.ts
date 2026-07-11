import { describe, expect, it } from "vitest";
import { statusStyle, formatMetrics, clampVariantCount } from "./branchHelpers";

describe("statusStyle", () => {
  it("GENERATING → blue border with pulse animation", () => {
    const s = statusStyle("GENERATING");
    expect(s.borderColor).toBe("#58a6ff");
    expect(s.animationName).toBe("branchPulse");
    expect(s.opacity).toBe(1);
  });

  it("READY → dim border, no animation", () => {
    const s = statusStyle("READY");
    expect(s.borderColor).toBe("#6e7681");
    expect(s.animationName).toBeUndefined();
    expect(s.opacity).toBe(1);
  });

  it("SELECTED → green border", () => {
    const s = statusStyle("SELECTED");
    expect(s.borderColor).toBe("#3fb950");
  });

  it("DISCARDED → dark border, reduced opacity", () => {
    const s = statusStyle("DISCARDED");
    expect(s.opacity).toBeLessThan(0.5);
    expect(s.animationName).toBeUndefined();
  });
});

describe("formatMetrics", () => {
  const base = { filesChanged: 3, blastRadiusSize: 12, debtDelta: 0, confidence: 82, verified: false };

  it("formats positive debtDelta with + sign", () => {
    const m = formatMetrics({ ...base, debtDelta: 5 });
    expect(m.debt).toBe("+5");
  });

  it("formats zero debtDelta without sign", () => {
    const m = formatMetrics({ ...base, debtDelta: 0 });
    expect(m.debt).toBe("0");
  });

  it("formats negative debtDelta correctly", () => {
    const m = formatMetrics({ ...base, debtDelta: -3 });
    expect(m.debt).toBe("-3");
  });

  it("appends % to confidence", () => {
    const m = formatMetrics(base);
    expect(m.confidence).toBe("82%");
  });

  it("converts filesChanged and blastRadiusSize to string", () => {
    const m = formatMetrics(base);
    expect(m.files).toBe("3");
    expect(m.blast).toBe("12");
  });
});

describe("clampVariantCount", () => {
  it("clamps values below 2 to 2", () => {
    expect(clampVariantCount(0)).toBe(2);
    expect(clampVariantCount(1)).toBe(2);
    expect(clampVariantCount(-5)).toBe(2);
  });

  it("clamps values above 4 to 4", () => {
    expect(clampVariantCount(5)).toBe(4);
    expect(clampVariantCount(100)).toBe(4);
  });

  it("passes through valid values 2-4", () => {
    expect(clampVariantCount(2)).toBe(2);
    expect(clampVariantCount(3)).toBe(3);
    expect(clampVariantCount(4)).toBe(4);
  });

  it("rounds fractional values", () => {
    expect(clampVariantCount(2.4)).toBe(2);
    expect(clampVariantCount(2.6)).toBe(3);
  });
});
