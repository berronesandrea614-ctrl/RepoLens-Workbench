/**
 * Feature C: Spec↔Implementation Bidirectional Traceability — shared TypeScript types.
 * Mirrors TraceMapVO, TraceForwardVO, TraceReverseVO from the Java backend.
 */

export interface TraceMetrics {
  coverage: number;
  orphanCount: number;
  danglingCount: number;
  staleCount: number;
}

export interface TraceNode {
  /** "req" | "sym" */
  nodeType: "req" | "sym";
  id: string;
  label: string;
  layer: string | null;
  /** "dangling" | "orphan" | null */
  flag: "dangling" | "orphan" | null;
}

export interface TraceEdge {
  source: string;
  target: string;
  linkType: string;
  confidence: number;
  /** "stale" = STALE/BROKEN link */
  status: string;
}

export interface TraceMapVO {
  metrics: TraceMetrics;
  nodes: TraceNode[];
  edges: TraceEdge[];
  /** true = vector/LLM unavailable; only DECLARED links, may under-estimate coverage */
  degraded: boolean;
}

export interface TraceLink {
  symbolId: number;
  filePath: string;
  startLine: number | null;
  linkType: string;
  confidence: number;
  status: string;
  symbolName: string;
  layer: string;
}

export interface TraceForwardVO {
  requirementId: number;
  title: string;
  coverage: number;
  links: TraceLink[];
}

export interface ReqLink {
  requirementId: number;
  title: string;
  linkType: string;
  confidence: number;
  status: string;
}

export interface TraceReverseVO {
  symbolId: number;
  symbolName: string;
  layer: string;
  requirements: ReqLink[];
}
