import { describe, expect, it } from "vitest";
import {
  buildSummaryLabel,
  collectLayers,
  compareByLayer,
  coverageColor,
  formatCoverage,
  isStaleEdge,
  nodeFlagLabel,
  partitionNodes,
} from "./traceabilityUtils";
import type { TraceEdge, TraceMetrics, TraceNode } from "./traceabilityTypes";

const makeNode = (
  id: string,
  nodeType: "req" | "sym",
  layer?: string,
  flag?: TraceNode["flag"],
): TraceNode => ({ id, nodeType, label: id, layer: layer ?? null, flag: flag ?? null });

const makeEdge = (status: string): TraceEdge => ({
  source: "req-1",
  target: "sym-1",
  linkType: "DECLARED",
  confidence: 1.0,
  status,
});

describe("formatCoverage", () => {
  it("formats 0.75 as 75%", () => {
    expect(formatCoverage(0.75)).toBe("75%");
  });
  it("formats 1.0 as 100%", () => {
    expect(formatCoverage(1.0)).toBe("100%");
  });
  it("formats 0.0 as 0%", () => {
    expect(formatCoverage(0.0)).toBe("0%");
  });
  it("rounds 0.333 as 33%", () => {
    expect(formatCoverage(0.333)).toBe("33%");
  });
});

describe("coverageColor", () => {
  it("returns green for 0.8+", () => {
    expect(coverageColor(0.8)).toBe("green");
    expect(coverageColor(1.0)).toBe("green");
  });
  it("returns yellow for 0.5-0.79", () => {
    expect(coverageColor(0.5)).toBe("yellow");
    expect(coverageColor(0.79)).toBe("yellow");
  });
  it("returns red below 0.5", () => {
    expect(coverageColor(0.0)).toBe("red");
    expect(coverageColor(0.49)).toBe("red");
  });
});

describe("nodeFlagLabel", () => {
  it("returns dangling label", () => {
    expect(nodeFlagLabel("dangling")).toContain("dangling");
  });
  it("returns orphan label", () => {
    expect(nodeFlagLabel("orphan")).toContain("orphan");
  });
  it("returns empty for null", () => {
    expect(nodeFlagLabel(null)).toBe("");
  });
});

describe("isStaleEdge", () => {
  it("true for status stale", () => {
    expect(isStaleEdge(makeEdge("stale"))).toBe(true);
  });
  it("true for status STALE", () => {
    expect(isStaleEdge(makeEdge("STALE"))).toBe(true);
  });
  it("true for status BROKEN", () => {
    expect(isStaleEdge(makeEdge("BROKEN"))).toBe(true);
  });
  it("false for linked", () => {
    expect(isStaleEdge(makeEdge("linked"))).toBe(false);
  });
  it("false for LINKED", () => {
    expect(isStaleEdge(makeEdge("LINKED"))).toBe(false);
  });
});

describe("buildSummaryLabel", () => {
  it("includes coverage", () => {
    const m: TraceMetrics = { coverage: 0.8, orphanCount: 0, danglingCount: 0, staleCount: 0 };
    expect(buildSummaryLabel(m)).toContain("80%");
  });
  it("includes orphan count when nonzero", () => {
    const m: TraceMetrics = { coverage: 0.6, orphanCount: 3, danglingCount: 0, staleCount: 0 };
    expect(buildSummaryLabel(m)).toContain("3 orphan");
  });
  it("includes dangling count when nonzero", () => {
    const m: TraceMetrics = { coverage: 0.5, orphanCount: 0, danglingCount: 2, staleCount: 1 };
    const label = buildSummaryLabel(m);
    expect(label).toContain("2 dangling");
    expect(label).toContain("1 stale");
  });
  it("omits zero counts", () => {
    const m: TraceMetrics = { coverage: 1.0, orphanCount: 0, danglingCount: 0, staleCount: 0 };
    const label = buildSummaryLabel(m);
    expect(label).not.toContain("orphan");
    expect(label).not.toContain("dangling");
    expect(label).not.toContain("stale");
  });
});

describe("partitionNodes", () => {
  it("splits req and sym nodes", () => {
    const nodes = [
      makeNode("req-1", "req"),
      makeNode("sym-1", "sym", "Service"),
      makeNode("req-2", "req"),
    ];
    const { reqNodes, symNodes } = partitionNodes(nodes);
    expect(reqNodes).toHaveLength(2);
    expect(symNodes).toHaveLength(1);
  });
});

describe("compareByLayer", () => {
  it("orders Controller before Service", () => {
    const c = makeNode("c", "sym", "Controller");
    const s = makeNode("s", "sym", "Service");
    expect(compareByLayer(c, s)).toBeLessThan(0);
    expect(compareByLayer(s, c)).toBeGreaterThan(0);
  });
  it("unknown layer goes last", () => {
    const s = makeNode("s", "sym", "Service");
    const u = makeNode("u", "sym", "Unknown");
    expect(compareByLayer(s, u)).toBeLessThan(0);
  });
});

describe("collectLayers", () => {
  it("returns unique layers in canonical order", () => {
    const nodes = [
      makeNode("m1", "sym", "Mapper"),
      makeNode("c1", "sym", "Controller"),
      makeNode("s1", "sym", "Service"),
      makeNode("m2", "sym", "Mapper"),
    ];
    expect(collectLayers(nodes)).toEqual(["Controller", "Service", "Mapper"]);
  });
  it("returns empty for nodes without layer", () => {
    const nodes = [makeNode("r1", "req")];
    expect(collectLayers(nodes)).toEqual([]);
  });
});
