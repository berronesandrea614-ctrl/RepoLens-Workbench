import { describe, expect, it } from "vitest";
import {
  initialVisible,
  hiddenNeighborCount,
  expand,
  subgraph,
} from "./graphExpansion";
import { CodeGraph, GraphNode, GraphEdge } from "../../types/graph";

function node(id: string): GraphNode {
  return { id, label: id, symbolType: "METHOD", resolved: true };
}
function edge(source: string, target: string): GraphEdge {
  return { id: `${source}->${target}`, source, target, relationType: "CALLS", confidence: 1 };
}

// A -> B -> C -> D chain plus A -> E branch. Root = A.
const chain: CodeGraph = {
  rootId: "A",
  nodes: ["A", "B", "C", "D", "E"].map(node),
  edges: [edge("A", "B"), edge("B", "C"), edge("C", "D"), edge("A", "E")],
  nodeCount: 5,
  edgeCount: 4,
  truncated: false,
};

// A star with 100 leaves around a hub, root = hub.
function star(leaves: number): CodeGraph {
  const nodes: GraphNode[] = [node("hub")];
  const edges: GraphEdge[] = [];
  for (let i = 0; i < leaves; i++) {
    nodes.push(node(`leaf${i}`));
    edges.push(edge("hub", `leaf${i}`));
  }
  return { rootId: "hub", nodes, edges, nodeCount: nodes.length, edgeCount: edges.length, truncated: false };
}

describe("initialVisible", () => {
  it("depth 1 = root + direct neighbours only (undirected)", () => {
    const v = initialVisible(chain, "A", 1);
    expect([...v].sort()).toEqual(["A", "B", "E"]);
    expect(v.has("C")).toBe(false);
    expect(v.has("D")).toBe(false);
  });

  it("depth 2 reaches two hops away", () => {
    const v = initialVisible(chain, "A", 2);
    expect([...v].sort()).toEqual(["A", "B", "C", "E"]);
    expect(v.has("D")).toBe(false);
  });

  it("traverses edges in both directions", () => {
    // From D (a leaf of the chain) depth 1 must include its predecessor C.
    const v = initialVisible(chain, "D", 1);
    expect([...v].sort()).toEqual(["C", "D"]);
  });

  it("falls back to the first node when rootId is missing", () => {
    const v = initialVisible(chain, "ZZZ", 1);
    expect(v.has("A")).toBe(true);
  });

  it("caps a dense depth-1 so it stays readable", () => {
    const v = initialVisible(star(200), "hub", 1, 60);
    expect(v.size).toBe(60);
    expect(v.has("hub")).toBe(true);
  });

  it("returns an empty set for an empty graph", () => {
    const empty: CodeGraph = { rootId: "", nodes: [], edges: [], nodeCount: 0, edgeCount: 0, truncated: false };
    expect(initialVisible(empty, "A").size).toBe(0);
  });
});

describe("hiddenNeighborCount", () => {
  it("counts neighbours (both directions) not in the visible set", () => {
    const visible = new Set(["A", "B", "E"]); // depth-1 view of the chain
    // B's neighbours are A (visible) and C (hidden) -> 1 hidden
    expect(hiddenNeighborCount(chain, "B", visible)).toBe(1);
    // A's neighbours B & E are both visible -> 0 hidden
    expect(hiddenNeighborCount(chain, "A", visible)).toBe(0);
  });

  it("is zero when every neighbour is already visible", () => {
    const all = new Set(chain.nodes.map((n) => n.id));
    expect(hiddenNeighborCount(chain, "B", all)).toBe(0);
  });
});

describe("expand", () => {
  it("adds a node's hidden neighbours and grows the set", () => {
    const visible = new Set(["A", "B", "E"]);
    const grown = expand(chain, "B", visible);
    expect(grown.size).toBeGreaterThan(visible.size);
    expect(grown.has("C")).toBe(true);
    // original set is not mutated
    expect(visible.has("C")).toBe(false);
  });

  it("caps how many neighbours a single expand can add", () => {
    const s = star(200);
    const grown = expand(s, "hub", new Set(["hub"]), 40);
    expect(grown.size).toBe(41); // hub + 40 leaves
  });
});

describe("subgraph", () => {
  it("keeps only visible nodes and fully-visible edges", () => {
    const visible = new Set(["A", "B", "E"]);
    const sub = subgraph(chain, visible);
    expect(sub.nodes.map((n) => n.id).sort()).toEqual(["A", "B", "E"]);
    // A->B and A->E kept; B->C dropped (C hidden)
    expect(sub.edges.map((e) => e.id).sort()).toEqual(["A->B", "A->E"]);
    expect(sub.nodeCount).toBe(3);
    expect(sub.edgeCount).toBe(2);
    expect(sub.rootId).toBe("A");
  });

  it("drops an edge when either endpoint is hidden", () => {
    const sub = subgraph(chain, new Set(["B"]));
    expect(sub.edges).toHaveLength(0);
    expect(sub.nodes.map((n) => n.id)).toEqual(["B"]);
  });
});
