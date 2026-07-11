import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import ReactFlow, { Background, Controls, Node, Edge } from "reactflow";
import "reactflow/dist/style.css";
import "./graph.css";
import { CodeGraph, GraphNode } from "../../types/graph";
import { layoutGraph, nodeColor, LAYER_COLORS } from "./graphLayout";
import { edgeKey, TraceResult } from "./graphTrace";
import {
  initialVisible,
  hiddenNeighborCount,
  expand,
  subgraph,
} from "./graphExpansion";

export interface GraphCanvasProps {
  graph: CodeGraph;
  /** Jump to a file/line when a node is single-clicked (default action). */
  openFile: (filePath: string, line: number) => void;

  // --- Controlled visible set (lifted to GraphView) ----------------------
  visible: Set<string>;
  onSetVisible: (v: Set<string>) => void;

  // --- Optional trace / focus overlay (used by GraphView) ---------------
  /** When true, a plain node click reports the node instead of jumping. */
  traceMode?: boolean;
  onNodeTrace?: (nodeId: string) => void;
  /** Highlight / dim overlay computed against the base graph. */
  highlight?: TraceResult | null;
  /** Clear the highlight when the empty pane is clicked. */
  onPaneClick?: () => void;
  /** Filter the view down to a focused neighbourhood. */
  focus?: TraceResult | null;
  /** Show a "聚焦邻居" action in the detail card when provided. */
  onFocusNode?: (nodeId: string) => void;
  onRestoreFocus?: () => void;

  /**
   * Feature A: 理解债务热力染色模式。
   * true = 用 debtColor（后端注入）替代 layer/symbolType 颜色；
   *        无债务数据的节点渲染为中性灰（区别于健康绿）。
   * false (default) = 沿用正常 layer 颜色。
   */
  debtMode?: boolean;
  /**
   * Feature J: 时间轴帧染色模式。
   * true = 节点按 changeType（NEW/MODIFIED/STABLE）染色，优先级高于 debtMode。
   * false (default) = 沿用 debtMode / 正常 layer 颜色。
   */
  frameColorMode?: boolean;
}

/**
 * Shared ReactFlow renderer with client-side PROGRESSIVE EXPANSION.
 *
 * The full graph may be hundreds of nodes; we only ever lay out and render the
 * `visible` subset (root + depth-1 initially), and the user grows it node by
 * node — like an IDE call hierarchy. This is what keeps a 437-node graph from
 * rendering as an unreadable hairball.
 */
export function GraphCanvas(props: GraphCanvasProps) {
  const { graph, openFile, visible, onSetVisible, traceMode, onNodeTrace, highlight, onPaneClick, focus, onFocusNode, onRestoreFocus, debtMode, frameColorMode } = props;

  const [hovered, setHovered] = useState<GraphNode | null>(null);
  const hideTimer = useRef<number | null>(null);
  const clearHideTimer = () => {
    if (hideTimer.current != null) { window.clearTimeout(hideTimer.current); hideTimer.current = null; }
  };
  const scheduleHide = useCallback(() => {
    clearHideTimer();
    hideTimer.current = window.setTimeout(() => setHovered(null), 140);
  }, []);
  useEffect(() => () => clearHideTimer(), []);

  const doExpand = useCallback((nodeId: string) => {
    onSetVisible(expand(graph, nodeId, visible));
  }, [graph, visible, onSetVisible]);

  const expandAll = useCallback(() => {
    const total = graph.nodes.length;
    if (total > 100 && !window.confirm(`即将展开全部 ${total} 个节点，图可能变得很密集。继续？`)) return;
    onSetVisible(new Set(graph.nodes.map((n) => n.id)));
  }, [graph, onSetVisible]);

  const reset = useCallback(() => onSetVisible(initialVisible(graph, graph.rootId)), [graph, onSetVisible]);

  // Layout memo — dagre + hidden-count pre-computation.
  // Only re-runs when graph, visible, debtMode, or frameColorMode changes (NOT on hover/trace).
  const layout = useMemo(() => {
    const sub = subgraph(graph, visible);
    const laid = layoutGraph(sub, debtMode, frameColorMode);
    const hiddenCounts = new Map<string, number>();
    for (const n of laid.nodes) {
      hiddenCounts.set(n.id, hiddenNeighborCount(graph, n.id, visible));
    }
    return { nodes: laid.nodes, edges: laid.edges, hiddenCounts };
  }, [graph, visible, debtMode, frameColorMode]);

  // Decoration memo — cheap overlay; only re-runs on focus/highlight change.
  const view = useMemo(() => {
    let nodes: Node[] = layout.nodes.map((n) => {
      const gn = n.data?.node as GraphNode | undefined;
      const hidden = layout.hiddenCounts.get(n.id) ?? 0;
      return {
        ...n,
        data: {
          ...n.data,
          label: (
            <div className="graph-node-inner">
              <span className="graph-node-text">{gn?.label ?? n.id}</span>
              {hidden > 0 && (
                <span className="graph-expand-badge" data-expand="1" title={`展开 ${hidden} 个隐藏的调用关系`}>
                  +{hidden}
                </span>
              )}
            </div>
          ),
        },
      };
    });
    let edges: Edge[] = layout.edges;

    if (focus) {
      nodes = nodes.filter((n) => focus.nodeIds.has(n.id));
      edges = edges.filter((e) => focus.edgeIds.has(edgeKey(e.source, e.target)));
    }

    if (highlight) {
      const hasPath = highlight.nodeIds.size > 0;
      nodes = nodes.map((n) => ({
        ...n,
        className: hasPath
          ? highlight.nodeIds.has(n.id) ? "graph-node-hl" : "graph-node-dim"
          : n.className,
      }));
      edges = edges.map((e) => ({
        ...e,
        className: hasPath
          ? highlight.edgeIds.has(edgeKey(e.source, e.target)) ? "graph-edge-hl" : "graph-edge-dim"
          : e.className,
      }));
    }

    return { nodes, edges };
  }, [layout, focus, highlight]);

  const onNodeClick = useCallback((event: React.MouseEvent, node: Node) => {
    // Clicking the "+N" badge expands the node's hidden neighbours.
    const target = event.target as HTMLElement | null;
    if (target?.closest?.(".graph-expand-badge")) {
      doExpand(node.id);
      return;
    }
    if (traceMode) { onNodeTrace?.(node.id); return; }
    const gn = node.data?.node as GraphNode | undefined;
    if (gn?.resolved && gn.filePath) openFile(gn.filePath, gn.startLine ?? 1);
  }, [traceMode, onNodeTrace, openFile, doExpand]);

  const onNodeDoubleClick = useCallback((_: unknown, node: Node) => {
    doExpand(node.id);
  }, [doExpand]);

  const onNodeMouseEnter = useCallback((_: unknown, node: Node) => {
    const gn = node.data?.node as GraphNode | undefined;
    if (gn) { clearHideTimer(); setHovered(gn); }
  }, []);
  const onNodeMouseLeave = useCallback(() => scheduleHide(), [scheduleHide]);

  const total = graph.nodes.length;
  const shown = view.nodes.length;

  return (
    <div className="graph-canvas">
      <div className="graph-expand-bar">
        <span className="hint">显示 {shown} / 共 {total} 节点</span>
        <button
          className="graph-restore-btn"
          onClick={expandAll}
          disabled={visible.size >= total}
          title="展开图中的全部节点（大图可能变得密集）"
        >
          展开全部 ({total} 节点)
        </button>
        <button className="graph-restore-btn" onClick={reset} title="回到根符号的初始视图">重置</button>
        <span className="hint">双击节点 / 点节点上的 +N 展开其调用关系</span>
      </div>

      <ReactFlow
        nodes={view.nodes}
        edges={view.edges}
        onNodeClick={onNodeClick}
        onNodeDoubleClick={onNodeDoubleClick}
        onPaneClick={onPaneClick}
        onNodeMouseEnter={onNodeMouseEnter}
        onNodeMouseLeave={onNodeMouseLeave}
        fitView
        minZoom={0.2}
      >
        <Background color="#30363d" gap={16} />
        <Controls />
      </ReactFlow>

      {traceMode && (
        <div className="graph-trace-hint">追踪模式：点击任一节点，高亮它到根符号的链路 · 点击空白处清除</div>
      )}

      {hovered && (
        <div className="graph-node-card" onMouseEnter={clearHideTimer} onMouseLeave={scheduleHide}>
          <div className="graph-node-card-head">
            <span className="graph-node-card-title">{hovered.label}</span>
            {hovered.layer && (
              <span className="graph-layer-badge" style={{ background: LAYER_COLORS[hovered.layer] ?? nodeColor(hovered) }}>
                {hovered.layer}
              </span>
            )}
          </div>
          {hovered.signature && <code className="graph-node-card-sig">{hovered.signature}</code>}
          {hovered.summary && <div className="graph-node-card-summary">{hovered.summary}</div>}
          {hovered.filePath && (
            <div className="graph-node-card-loc">{hovered.filePath}:{hovered.startLine ?? 1}</div>
          )}
          <div className="graph-node-card-actions">
            <button className="graph-focus-btn" onClick={() => doExpand(hovered.id)}>展开调用</button>
            {onFocusNode && <button className="graph-focus-btn" onClick={() => onFocusNode(hovered.id)}>聚焦邻居</button>}
            {focus && onRestoreFocus && <button className="graph-restore-btn" onClick={onRestoreFocus}>还原</button>}
          </div>
        </div>
      )}
    </div>
  );
}
