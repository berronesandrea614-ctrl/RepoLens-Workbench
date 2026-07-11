import dagre from "dagre";
import { Node, Edge, Position } from "reactflow";
import { CodeGraph } from "../../types/graph";

const NODE_W = 190;
const NODE_H = 44;

// Keyed by GraphNode.layer (Controller/Service/Mapper/Entity/External).
export const LAYER_COLORS: Record<string, string> = {
  Controller: "#8957e5",
  Service: "#1f6feb",
  Mapper: "#238636",
  Entity: "#9e6a03",
  External: "#484f58",
};

// Fallback mapping keyed by symbolType when layer is absent.
const COLORS: Record<string, string> = {
  API: "#8957e5",
  CONTROLLER: "#8957e5",
  SERVICE: "#1f6feb",
  METHOD: "#1f6feb",
  MAPPER: "#238636",
  ENTITY: "#6e7681",
  CLASS: "#9e6a03",
  EXTERNAL: "#484f58",
};

export function nodeColor(n: { layer?: string; symbolType: string }): string {
  if (n.layer && LAYER_COLORS[n.layer]) return LAYER_COLORS[n.layer];
  return COLORS[n.symbolType] ?? "#1f6feb";
}

/**
 * Feature A: 理解债务热力颜色。
 * score≥70 → RED(高危) / 40–69 → YELLOW(预警) / <40 → GREEN(健康) / null → 无数据（沿用 nodeColor）。
 */
export function debtColor(score: number | null | undefined): string | null {
  if (score == null) return null;
  if (score >= 70) return "#e74c3c"; // RED
  if (score >= 40) return "#f39c12"; // YELLOW
  return "#27ae60"; // GREEN
}

/**
 * Feature J: 时间轴帧染色——按节点变更类型返回颜色。
 * NEW      → 蓝（首次出现）
 * MODIFIED → 橙（有变动）
 * STABLE   → 深灰（无变动）
 * 默认      → 灰（无数据）
 */
export function changeTypeColor(changeType?: string): string {
  if (changeType === "NEW") return "#4daafc";
  if (changeType === "MODIFIED") return "#f39c12";
  if (changeType === "STABLE") return "#30363d";
  return "#484f58";
}

/**
 * Layout the graph and produce ReactFlow nodes/edges.
 *
 * @param graph          The code graph to lay out.
 * @param debtMode       When true, nodes that have debt data (debtColor≠null) are colored
 *                       by their debt score instead of the normal layer/symbolType color.
 *                       This is the "调用图热力染色" mode used by the debt dashboard.
 * @param frameColorMode When true (Feature J), nodes are colored by changeType
 *                       (NEW/MODIFIED/STABLE) instead of layer/debt colors.
 *                       Takes priority over debtMode.
 */
export function layoutGraph(
  graph: CodeGraph,
  debtMode = false,
  frameColorMode = false,
): { nodes: Node[]; edges: Edge[] } {
  const g = new dagre.graphlib.Graph();
  g.setGraph({ rankdir: "LR", nodesep: 24, ranksep: 70 });
  g.setDefaultEdgeLabel(() => ({}));

  graph.nodes.forEach((n) => g.setNode(n.id, { width: NODE_W, height: NODE_H }));
  graph.edges.forEach((e) => g.setEdge(e.source, e.target));
  dagre.layout(g);

  const nodes: Node[] = graph.nodes.map((n) => {
    const pos = g.node(n.id);
    // Color priority: frameColorMode (J) > debtMode (A) > default layer/symbolType.
    // In frameColorMode, color by changeType (NEW/MODIFIED/STABLE).
    // In debt mode, prefer the backend-injected debtColor; fall back to neutral gray.
    const color = frameColorMode
      ? changeTypeColor(n.changeType)
      : debtMode
        ? (n.debtColor ?? (n.resolved ? "#484f58" : "#30363d"))
        : nodeColor(n);
    return {
      id: n.id,
      position: { x: pos.x - NODE_W / 2, y: pos.y - NODE_H / 2 },
      data: { label: n.label, node: n },
      sourcePosition: Position.Right,
      targetPosition: Position.Left,
      style: {
        width: NODE_W, background: n.resolved ? "#161b22" : "#0d1117",
        color: "#e6edf3", border: `1px solid ${color}`, borderLeft: `4px solid ${color}`,
        borderRadius: 6, fontSize: 12, padding: "6px 8px",
        opacity: n.resolved ? 1 : 0.7,
      },
    };
  });

  const edges: Edge[] = graph.edges.map((e) => ({
    id: e.id, source: e.source, target: e.target,
    label: e.dataType ?? (e.relationType === "IMPLEMENTS" ? "impl" : undefined),
    labelStyle: e.dataType ? { fill: "#4daafc", fontSize: 11, fontFamily: "var(--vs-font-mono, monospace)" } : undefined,
    labelBgStyle: e.dataType ? { fill: "#161b22", fillOpacity: 0.9 } : undefined,
    style: {
      stroke: e.relationType === "IMPLEMENTS" ? "#9e6a03" : "#4daafc",
      strokeWidth: e.confidence >= 0.9 ? 2.2 : 1.2,
      strokeDasharray: e.confidence < 0.9 ? "5 4" : undefined,
    },
  }));

  return { nodes, edges };
}
