// Pure, client-side progressive-expansion helpers for the flow graphs.
//
// The backend returns the FULL graph (up to its own cap). To keep large graphs
// (hundreds of nodes) readable we only ever RENDER a subset of "visible" nodes,
// starting from the root + depth-1 neighbourhood and growing on demand.
//
// No React / DOM dependencies so these are trivially unit-testable.

import { CodeGraph } from "../../types/graph";

// Cap the initial collapsed view so even a very dense depth-1 stays readable.
export const INITIAL_CAP = 60;
// Add at most this many neighbours per expand so a single click cannot
// re-create the hairball.
export const EXPAND_CAP = 40;

/**
 * Undirected adjacency map: node id -> set of neighbour ids (both directions).
 * Every node in the graph gets an entry, even isolated ones.
 */
function buildAdjacency(graph: CodeGraph): Map<string, Set<string>> {
  const adj = new Map<string, Set<string>>();
  const ensure = (id: string): Set<string> => {
    let s = adj.get(id);
    if (!s) {
      s = new Set<string>();
      adj.set(id, s);
    }
    return s;
  };
  for (const n of graph.nodes) ensure(n.id);
  for (const e of graph.edges) {
    ensure(e.source).add(e.target);
    ensure(e.target).add(e.source);
  }
  return adj;
}

/** Resolve the effective root: the given id if present, else the first node. */
function resolveRoot(graph: CodeGraph, rootId: string): string | undefined {
  if (rootId && graph.nodes.some((n) => n.id === rootId)) return rootId;
  return graph.nodes[0]?.id;
}

/**
 * BFS from `rootId` over UNDIRECTED adjacency up to `depth` hops.
 * Returns the set of visible node ids. Falls back to the first node when
 * `rootId` is missing, and never exceeds `cap` nodes so a dense depth-1
 * still renders readably.
 */
export function initialVisible(
  graph: CodeGraph,
  rootId: string,
  depth = 1,
  cap = INITIAL_CAP
): Set<string> {
  const visible = new Set<string>();
  const root = resolveRoot(graph, rootId);
  if (root == null) return visible;

  const adj = buildAdjacency(graph);
  visible.add(root);
  let frontier: string[] = [root];

  for (let d = 0; d < depth && visible.size < cap; d++) {
    const next: string[] = [];
    for (const id of frontier) {
      for (const nb of adj.get(id) ?? []) {
        if (visible.has(nb)) continue;
        if (visible.size >= cap) break;
        visible.add(nb);
        next.push(nb);
      }
      if (visible.size >= cap) break;
    }
    frontier = next;
  }
  return visible;
}

/**
 * Number of `nodeId`'s direct neighbours (both directions) that are NOT yet
 * visible — i.e. how many nodes a "+N" badge should advertise.
 */
export function hiddenNeighborCount(
  graph: CodeGraph,
  nodeId: string,
  visible: Set<string>
): number {
  const adj = buildAdjacency(graph);
  let count = 0;
  for (const nb of adj.get(nodeId) ?? []) {
    if (!visible.has(nb)) count++;
  }
  return count;
}

/**
 * Return a NEW visible set = `visible` ∪ `nodeId`'s direct neighbours
 * (both directions), adding at most `cap` new nodes per call to avoid
 * re-hairballing on a single expand.
 */
export function expand(
  graph: CodeGraph,
  nodeId: string,
  visible: Set<string>,
  cap = EXPAND_CAP
): Set<string> {
  const next = new Set(visible);
  const adj = buildAdjacency(graph);
  let added = 0;
  for (const nb of adj.get(nodeId) ?? []) {
    if (next.has(nb)) continue;
    if (added >= cap) break;
    next.add(nb);
    added++;
  }
  return next;
}

/**
 * A CodeGraph restricted to `visible`: only nodes in the set, and only edges
 * whose BOTH endpoints are visible. Node/edge fields are preserved; counts are
 * recomputed; rootId and truncated are carried over.
 */
export function subgraph(graph: CodeGraph, visible: Set<string>): CodeGraph {
  const nodes = graph.nodes.filter((n) => visible.has(n.id));
  const edges = graph.edges.filter(
    (e) => visible.has(e.source) && visible.has(e.target)
  );
  return {
    rootId: graph.rootId,
    nodes,
    edges,
    nodeCount: nodes.length,
    edgeCount: edges.length,
    truncated: graph.truncated,
  };
}
