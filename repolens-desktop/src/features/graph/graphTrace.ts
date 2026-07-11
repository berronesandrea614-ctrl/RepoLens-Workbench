// Pure graph-tracing helpers for the flow view.
// No React / DOM dependencies so they are trivially unit-testable.

export interface TraceEdge {
  source: string;
  target: string;
}

export interface TraceResult {
  nodeIds: Set<string>;
  edgeIds: Set<string>;
}

export function edgeKey(source: string, target: string): string {
  return `${source}->${target}`;
}

/**
 * BFS over directed edges (source -> target) from `rootId` to `targetId`.
 * Returns the node ids and edge keys that lie on one shortest path.
 * Both sets are empty when the target is unreachable from the root.
 */
export function pathFromRoot(
  edges: TraceEdge[],
  rootId: string,
  targetId: string
): TraceResult {
  const empty: TraceResult = { nodeIds: new Set(), edgeIds: new Set() };
  if (!rootId) return empty;
  if (rootId === targetId) {
    return { nodeIds: new Set([rootId]), edgeIds: new Set() };
  }

  const out = new Map<string, string[]>();
  for (const e of edges) {
    if (!out.has(e.source)) out.set(e.source, []);
    out.get(e.source)!.push(e.target);
  }

  // BFS remembering each node's predecessor so we can reconstruct the path.
  const prev = new Map<string, string>();
  const visited = new Set<string>([rootId]);
  const queue: string[] = [rootId];
  let found = false;

  while (queue.length > 0) {
    const cur = queue.shift()!;
    if (cur === targetId) { found = true; break; }
    for (const next of out.get(cur) ?? []) {
      if (visited.has(next)) continue;
      visited.add(next);
      prev.set(next, cur);
      queue.push(next);
    }
  }

  if (!found) return empty;

  const nodeIds = new Set<string>();
  const edgeIds = new Set<string>();
  let node = targetId;
  nodeIds.add(node);
  while (node !== rootId) {
    const p = prev.get(node)!;
    edgeIds.add(edgeKey(p, node));
    nodeIds.add(p);
    node = p;
  }
  return { nodeIds, edgeIds };
}

/**
 * The 1-hop neighborhood of `nodeId`: the node itself, its direct
 * predecessors and successors, plus the edges connecting them.
 */
export function neighborhood(edges: TraceEdge[], nodeId: string): TraceResult {
  const nodeIds = new Set<string>([nodeId]);
  const edgeIds = new Set<string>();
  for (const e of edges) {
    if (e.source === nodeId) {
      nodeIds.add(e.target);
      edgeIds.add(edgeKey(e.source, e.target));
    } else if (e.target === nodeId) {
      nodeIds.add(e.source);
      edgeIds.add(edgeKey(e.source, e.target));
    }
  }
  return { nodeIds, edgeIds };
}
