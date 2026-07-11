import type { TraceEdge, TraceMetrics, TraceNode } from "./traceabilityTypes";

/**
 * Feature C: Pure utility functions for traceability UI.
 * No side effects — safe to unit-test without mocks.
 */

/** Coverage percentage string, e.g. "75%" */
export function formatCoverage(coverage: number): string {
  return `${Math.round(coverage * 100)}%`;
}

/** Semantic colour class for coverage bar */
export function coverageColor(coverage: number): "green" | "yellow" | "red" {
  if (coverage >= 0.8) return "green";
  if (coverage >= 0.5) return "yellow";
  return "red";
}

/** Badge text for a TraceNode flag */
export function nodeFlagLabel(flag: TraceNode["flag"]): string {
  if (flag === "dangling") return "🔴 dangling";
  if (flag === "orphan") return "⚪ orphan";
  return "";
}

/** True if a link edge represents a stale/broken connection (should render red-dashed) */
export function isStaleEdge(edge: TraceEdge): boolean {
  return edge.status === "stale" || edge.status === "STALE" || edge.status === "BROKEN";
}

/** Summary badge label: "{n} orphan(s), {m} dangling, coverage X%" */
export function buildSummaryLabel(metrics: TraceMetrics): string {
  const parts: string[] = [];
  parts.push(`Coverage: ${formatCoverage(metrics.coverage)}`);
  if (metrics.orphanCount > 0) parts.push(`${metrics.orphanCount} orphan`);
  if (metrics.danglingCount > 0) parts.push(`${metrics.danglingCount} dangling`);
  if (metrics.staleCount > 0) parts.push(`${metrics.staleCount} stale`);
  return parts.join(" · ");
}

/** Partition nodes into req-nodes and sym-nodes for bipartite layout */
export function partitionNodes(nodes: TraceNode[]): {
  reqNodes: TraceNode[];
  symNodes: TraceNode[];
} {
  return {
    reqNodes: nodes.filter((n) => n.nodeType === "req"),
    symNodes: nodes.filter((n) => n.nodeType === "sym"),
  };
}

/** Layer sort order for sym-nodes (Controller→Service→Mapper→Entity→other) */
const LAYER_ORDER: Record<string, number> = {
  Controller: 0,
  Service: 1,
  Mapper: 2,
  Entity: 3,
};

export function compareByLayer(a: TraceNode, b: TraceNode): number {
  const aOrd = a.layer != null ? (LAYER_ORDER[a.layer] ?? 99) : 99;
  const bOrd = b.layer != null ? (LAYER_ORDER[b.layer] ?? 99) : 99;
  return aOrd - bOrd;
}

/** Unique layers present in a set of sym-nodes, in canonical order */
export function collectLayers(symNodes: TraceNode[]): string[] {
  const seen = new Set<string>();
  const result: string[] = [];
  [...symNodes].sort(compareByLayer).forEach((n) => {
    if (n.layer && !seen.has(n.layer)) {
      seen.add(n.layer);
      result.push(n.layer);
    }
  });
  return result;
}
