import { describe, it, expect } from "vitest";
import {
  isFlowNode,
  isNodeClickable,
  getNodeAction,
  getStepCls,
  shouldShowDeviation,
  isDegradeMode,
  isNoplanMode,
  formatDelta,
  buildChipLabels,
} from "./insightUtils";
import type { FlowNode, FlowEdge } from "./insightTypes";

const makeNode = (overrides: Partial<FlowNode> = {}): FlowNode => ({
  nodeType: "node",
  ...overrides,
});

const makeEdge = (overrides: Partial<FlowEdge> = {}): FlowEdge => ({
  nodeType: "edge",
  ...overrides,
});

// ── isFlowNode ─────────────────────────────────────────────────────────────
describe("isFlowNode", () => {
  it("returns true for node items", () => {
    expect(isFlowNode(makeNode())).toBe(true);
  });
  it("returns false for edge items", () => {
    expect(isFlowNode(makeEdge())).toBe(false);
  });
});

// ── isNodeClickable ─────────────────────────────────────────────────────────
describe("isNodeClickable", () => {
  it("returns false for external nodes", () => {
    expect(isNodeClickable(makeNode({ external: true, filePath: "a.java" }))).toBe(false);
  });
  it("returns false for dim nodes", () => {
    expect(isNodeClickable(makeNode({ cls: "dim", filePath: "a.java" }))).toBe(false);
  });
  it("returns false when no changeId and no filePath", () => {
    expect(isNodeClickable(makeNode({ cls: "new" }))).toBe(false);
  });
  it("returns true when changeId is set", () => {
    expect(isNodeClickable(makeNode({ changeId: 42, cls: "new" }))).toBe(true);
  });
  it("returns true when filePath is set", () => {
    expect(isNodeClickable(makeNode({ filePath: "src/Foo.java" }))).toBe(true);
  });
});

// ── getNodeAction ───────────────────────────────────────────────────────────
describe("getNodeAction", () => {
  it("returns diff for node with changeId", () => {
    expect(getNodeAction(makeNode({ changeId: 1, cls: "mod" }))).toBe("diff");
  });
  it("prefers diff over openFile when both present", () => {
    expect(getNodeAction(makeNode({ changeId: 1, filePath: "a.java", cls: "new" }))).toBe("diff");
  });
  it("returns openFile when only filePath present", () => {
    expect(getNodeAction(makeNode({ filePath: "a.java", cls: "new" }))).toBe("openFile");
  });
  it("returns none for external node", () => {
    expect(getNodeAction(makeNode({ external: true, changeId: 1 }))).toBe("none");
  });
  it("returns none for dim node", () => {
    expect(getNodeAction(makeNode({ cls: "dim", filePath: "a.java" }))).toBe("none");
  });
});

// ── getStepCls ──────────────────────────────────────────────────────────────
describe("getStepCls", () => {
  it("returns 'risk' for risk kind", () => {
    expect(getStepCls("risk")).toBe("risk");
  });
  it("returns 'off' for off kind", () => {
    expect(getStepCls("off")).toBe("off");
  });
  it("returns empty string for in kind", () => {
    expect(getStepCls("in")).toBe("");
  });
  it("returns empty string for unknown kind", () => {
    expect(getStepCls("unknown")).toBe("");
  });
});

// ── shouldShowDeviation ─────────────────────────────────────────────────────
describe("shouldShowDeviation", () => {
  it("returns false when deviation is null", () => {
    expect(shouldShowDeviation(null)).toBe(false);
  });
  it("returns false when deviation is undefined", () => {
    expect(shouldShowDeviation(undefined)).toBe(false);
  });
  it("returns false when files array is empty", () => {
    expect(shouldShowDeviation({ files: [], note: "x" })).toBe(false);
  });
  it("returns true when files is non-empty", () => {
    expect(shouldShowDeviation({ files: ["src/Foo.java"], note: "x" })).toBe(true);
  });
});

// ── isDegradeMode ───────────────────────────────────────────────────────────
describe("isDegradeMode (pure-ask)", () => {
  it("returns true when hasChanges is false", () => {
    expect(isDegradeMode({ hasChanges: false })).toBe(true);
  });
  it("returns false when hasChanges is true", () => {
    expect(isDegradeMode({ hasChanges: true })).toBe(false);
  });
});

// ── isNoplanMode ────────────────────────────────────────────────────────────
describe("isNoplanMode", () => {
  it("returns true when hasChanges=true and planned=false", () => {
    expect(isNoplanMode({ hasChanges: true, planned: false })).toBe(true);
  });
  it("returns false when planned=true", () => {
    expect(isNoplanMode({ hasChanges: true, planned: true })).toBe(false);
  });
  it("returns false when hasChanges=false (pure-ask wins)", () => {
    expect(isNoplanMode({ hasChanges: false, planned: false })).toBe(false);
  });
});

// ── formatDelta ─────────────────────────────────────────────────────────────
describe("formatDelta", () => {
  it("returns the delta string as-is", () => {
    expect(formatDelta("+46")).toBe("+46");
    expect(formatDelta("~9")).toBe("~9");
  });
  it("returns empty string for null", () => {
    expect(formatDelta(null)).toBe("");
  });
  it("returns empty string for undefined", () => {
    expect(formatDelta(undefined)).toBe("");
  });
});

// ── buildChipLabels ─────────────────────────────────────────────────────────
describe("buildChipLabels", () => {
  it("returns empty array for null chips", () => {
    expect(buildChipLabels(null)).toEqual([]);
  });
  it("includes off-plan chip in red when offPlanCount > 0", () => {
    const chips = buildChipLabels({
      filesChanged: 4, added: 2, modified: 2,
      plannedStepsDone: 3, plannedStepsTotal: 3, offPlanCount: 1,
    });
    const red = chips.filter((c) => c.cls === "r");
    expect(red.length).toBeGreaterThan(0);
    expect(red[0].text).toContain("计划外");
  });
  it("shows green no-deviation chip when offPlanCount=0 and has planned steps", () => {
    const chips = buildChipLabels({
      filesChanged: 3, added: 1, modified: 2,
      plannedStepsDone: 3, plannedStepsTotal: 3, offPlanCount: 0,
    });
    const green = chips.filter((c) => c.cls === "g" && c.text.includes("无计划外"));
    expect(green.length).toBe(1);
  });
});

// ── generation guard (async logic) ─────────────────────────────────────────
describe("generation guard (async logic)", () => {
  it("discards stale response when a newer request arrives first", async () => {
    // gen ref starts at 0
    let gen = 0;
    const committed: number[] = [];

    // Simulates the useEffect+useRef pattern used in RequirementInsightCard
    async function guardedLoad(myGen: number, delayMs: number) {
      await new Promise<void>((r) => setTimeout(r, delayMs));
      if (gen !== myGen) return; // stale — discard
      committed.push(myGen);
    }

    // req1: gen=1, slow (30ms); req2: gen=2, fast (0ms)
    gen = ++gen; const p1 = guardedLoad(gen, 30);
    gen = ++gen; const p2 = guardedLoad(gen, 0);
    await Promise.all([p1, p2]);

    // Only gen=2 should have been committed; gen=1 is stale
    expect(committed).toEqual([2]);
  });

  it("commits when there is only one request (no race)", async () => {
    let gen = 0;
    const committed: number[] = [];

    async function guardedLoad(myGen: number) {
      await Promise.resolve();
      if (gen !== myGen) return;
      committed.push(myGen);
    }

    gen = ++gen;
    await guardedLoad(gen);
    expect(committed).toEqual([1]);
  });
});
