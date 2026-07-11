import { describe, expect, it } from "vitest";
import { pathFromRoot, neighborhood, edgeKey } from "./graphTrace";

const chain = [
  { source: "A", target: "B" },
  { source: "B", target: "C" },
];

describe("pathFromRoot", () => {
  it("finds a path along a chain A->B->C", () => {
    const r = pathFromRoot(chain, "A", "C");
    expect([...r.nodeIds].sort()).toEqual(["A", "B", "C"]);
    expect(r.edgeIds.has(edgeKey("A", "B"))).toBe(true);
    expect(r.edgeIds.has(edgeKey("B", "C"))).toBe(true);
    expect(r.edgeIds.size).toBe(2);
  });

  it("returns just the root when target equals root", () => {
    const r = pathFromRoot(chain, "A", "A");
    expect([...r.nodeIds]).toEqual(["A"]);
    expect(r.edgeIds.size).toBe(0);
  });

  it("returns empty sets when target is unreachable", () => {
    const r = pathFromRoot(chain, "A", "Z");
    expect(r.nodeIds.size).toBe(0);
    expect(r.edgeIds.size).toBe(0);
  });

  it("does not traverse edges backwards (directed)", () => {
    // C cannot reach A going source->target
    const r = pathFromRoot(chain, "C", "A");
    expect(r.nodeIds.size).toBe(0);
    expect(r.edgeIds.size).toBe(0);
  });

  it("picks a shortest path when several exist", () => {
    const diamond = [
      { source: "A", target: "B" },
      { source: "B", target: "D" },
      { source: "A", target: "D" }, // direct shortcut
    ];
    const r = pathFromRoot(diamond, "A", "D");
    expect([...r.nodeIds].sort()).toEqual(["A", "D"]);
    expect(r.edgeIds.has(edgeKey("A", "D"))).toBe(true);
    expect(r.edgeIds.has(edgeKey("A", "B"))).toBe(false);
  });
});

describe("neighborhood", () => {
  it("returns node plus 1-hop predecessors and successors", () => {
    const edges = [
      { source: "A", target: "B" }, // predecessor of B
      { source: "B", target: "C" }, // successor of B
      { source: "X", target: "Y" }, // unrelated
    ];
    const r = neighborhood(edges, "B");
    expect([...r.nodeIds].sort()).toEqual(["A", "B", "C"]);
    expect(r.edgeIds.has(edgeKey("A", "B"))).toBe(true);
    expect(r.edgeIds.has(edgeKey("B", "C"))).toBe(true);
    expect(r.edgeIds.has(edgeKey("X", "Y"))).toBe(false);
    expect(r.edgeIds.size).toBe(2);
  });

  it("returns a singleton for an isolated node", () => {
    const r = neighborhood([{ source: "X", target: "Y" }], "Z");
    expect([...r.nodeIds]).toEqual(["Z"]);
    expect(r.edgeIds.size).toBe(0);
  });
});
