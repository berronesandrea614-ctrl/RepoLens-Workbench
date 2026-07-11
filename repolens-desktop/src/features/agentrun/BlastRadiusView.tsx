import React, { useCallback, useEffect, useMemo, useState } from "react";
import ReactFlow, {
  Background,
  Controls,
  Edge,
  Node,
  Position,
} from "reactflow";
import dagre from "dagre";
import "reactflow/dist/style.css";
import { fetchChangeGraph } from "../../api/agentRunApi";
import { fetchChanges } from "../../api/changeApi";
import {
  BlastGraphNode,
  ChangeGraph,
} from "../../types/agentRun";
import { FileChangeDetail } from "../../types/change";
import { DiffModal } from "../chat/DiffModal";

interface BlastRadiusViewProps {
  repoId: number;
  runId: number;
  sessionId?: number;
  openFile: (filePath: string, line?: number) => void;
}

// ── Layout constants ───────────────────────────────────────────────
const NODE_W = 180;
const NODE_H = 48;

// Colors
const COLOR_CHANGED = "#e3b341";    // gold — changed symbol
const COLOR_UPSTREAM = "#f0883e";   // orange — upstream callers
const COLOR_DOWNSTREAM = "#58a6ff"; // blue — downstream callees
const COLOR_BG = "#1c1a12";        // warm dark for changed nodes
const COLOR_BG_UP = "#1a1409";     // darker warm for upstream
const COLOR_BG_DOWN = "#0d1117";   // default dark for downstream

type NodeRole = "changed" | "upstream" | "downstream";

interface LayoutResult {
  rfNodes: Node[];
  rfEdges: Edge[];
}

function buildLayout(graph: ChangeGraph): LayoutResult {
  const g = new dagre.graphlib.Graph();
  g.setGraph({ rankdir: "LR", nodesep: 32, ranksep: 80 });
  g.setDefaultEdgeLabel(() => ({}));

  const roleMap = new Map<string, NodeRole>();

  // Changed symbols (center)
  for (const n of graph.changedSymbols) {
    g.setNode(n.id, { width: NODE_W, height: NODE_H, rank: 1 });
    roleMap.set(n.id, "changed");
  }

  // Upstream nodes
  const upNodeIds = new Set(graph.upstream.nodes.map((n) => n.id));
  for (const n of graph.upstream.nodes) {
    if (!roleMap.has(n.id)) {
      g.setNode(n.id, { width: NODE_W, height: NODE_H });
      roleMap.set(n.id, "upstream");
    }
  }

  // Downstream nodes
  for (const n of graph.downstream.nodes) {
    if (!roleMap.has(n.id)) {
      g.setNode(n.id, { width: NODE_W, height: NODE_H });
      roleMap.set(n.id, "downstream");
    }
  }

  // Edges: upstream edges point toward changed symbols
  for (const e of graph.upstream.edges) {
    if (g.hasNode(e.source) && g.hasNode(e.target)) {
      g.setEdge(e.source, e.target);
    }
  }
  // Downstream edges: changed → downstream
  for (const e of graph.downstream.edges) {
    if (g.hasNode(e.source) && g.hasNode(e.target)) {
      g.setEdge(e.source, e.target);
    }
  }

  dagre.layout(g);

  const allBlastNodes: BlastGraphNode[] = [
    ...graph.changedSymbols,
    ...graph.upstream.nodes,
    ...graph.downstream.nodes,
  ];
  const nodeById = new Map<string, BlastGraphNode>();
  for (const n of allBlastNodes) nodeById.set(n.id, n);

  const rfNodes: Node[] = [];
  const seen = new Set<string>();

  for (const nodeId of g.nodes()) {
    if (seen.has(nodeId)) continue;
    seen.add(nodeId);
    const pos = g.node(nodeId);
    if (!pos) continue;
    const role = roleMap.get(nodeId) ?? "downstream";
    const data = nodeById.get(nodeId);

    const isChanged = role === "changed";
    const color = isChanged
      ? COLOR_CHANGED
      : role === "upstream"
        ? COLOR_UPSTREAM
        : COLOR_DOWNSTREAM;
    const bg = isChanged
      ? COLOR_BG
      : role === "upstream"
        ? COLOR_BG_UP
        : COLOR_BG_DOWN;

    rfNodes.push({
      id: nodeId,
      position: { x: (pos.x ?? 0) - NODE_W / 2, y: (pos.y ?? 0) - NODE_H / 2 },
      data: {
        label: data?.label ?? nodeId,
        role,
        filePath: data?.filePath,
        changeType: data?.changeType,
        startLine: data?.startLine,
      },
      sourcePosition: Position.Right,
      targetPosition: Position.Left,
      style: {
        width: NODE_W,
        background: bg,
        color: "#e6edf3",
        border: isChanged ? `2px solid ${color}` : `1px solid ${color}`,
        borderLeft: `4px solid ${color}`,
        borderRadius: 6,
        fontSize: 11,
        padding: "6px 8px",
        boxSizing: "border-box" as const,
        overflow: "hidden",
        whiteSpace: "nowrap" as const,
        textOverflow: "ellipsis",
      },
    });
  }

  // Changed symbol indicator badge via label prefix
  for (const rn of rfNodes) {
    if ((rn.data as { role: NodeRole }).role === "changed") {
      rn.data = { ...rn.data, label: "✏ " + (rn.data as { label: string }).label };
    }
  }

  const rfEdges: Edge[] = [];
  const allEdges = [...graph.upstream.edges, ...graph.downstream.edges];
  const seenEdge = new Set<string>();
  for (const e of allEdges) {
    if (seenEdge.has(e.id)) continue;
    seenEdge.add(e.id);
    if (!g.hasNode(e.source) || !g.hasNode(e.target)) continue;
    const isUpstream = upNodeIds.has(e.source);
    rfEdges.push({
      id: e.id,
      source: e.source,
      target: e.target,
      style: {
        stroke: isUpstream ? COLOR_UPSTREAM : COLOR_DOWNSTREAM,
        strokeWidth: 1.6,
      },
      animated: false,
    });
  }

  return { rfNodes, rfEdges };
}

/** 影响面图：被改符号居中，上游调用方（橙）在左，下游被调方（蓝）在右。 */
export function BlastRadiusView({ repoId, runId, sessionId, openFile }: BlastRadiusViewProps) {
  const [graph, setGraph] = useState<ChangeGraph | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();

  // Diff modal state
  const [diff, setDiff] = useState<{ filePath: string; old: string; neu: string } | null>(null);
  const [diffLoading, setDiffLoading] = useState(false);
  const [changeDetails, setChangeDetails] = useState<FileChangeDetail[]>();

  // Load change-graph
  useEffect(() => {
    let ignore = false;
    setLoading(true);
    setError(undefined);
    setGraph(null);
    setChangeDetails(undefined);
    fetchChangeGraph(repoId, runId)
      .then((g) => {
        if (ignore) return;
        setGraph(g);
      })
      .catch((e: unknown) => {
        if (ignore) return;
        setError(e instanceof Error ? e.message : String(e));
      })
      .finally(() => {
        if (!ignore) setLoading(false);
      });
    return () => {
      ignore = true;
    };
  }, [repoId, runId]);

  const { rfNodes, rfEdges } = useMemo(() => {
    if (!graph) return { rfNodes: [], rfEdges: [] };
    return buildLayout(graph);
  }, [graph]);

  const onNodeClick = useCallback(
    (_: React.MouseEvent, node: Node) => {
      const { filePath, startLine, changeType } = node.data as {
        role: NodeRole;
        filePath?: string;
        startLine?: number;
        changeType?: string;
      };
      if (!filePath) return;

      if (changeType === "MODIFIED" && graph) {
        // Open diff modal for changed node
        setDiffLoading(true);
        setDiff({ filePath, old: "", neu: "" });

        const loadDiff = async () => {
          try {
            let details = changeDetails;
            if (!details && sessionId != null) {
              details = await fetchChanges(repoId, sessionId);
              setChangeDetails(details);
            }
            const found = details?.find((d) => d.filePath === filePath);
            if (found) {
              setDiff({ filePath: found.filePath, old: found.oldContent ?? "", neu: found.newContent ?? "" });
            } else {
              // Fallback: open file
              setDiff(null);
              openFile(filePath, startLine ?? 1);
            }
          } catch {
            setDiff(null);
            openFile(filePath, startLine ?? 1);
          } finally {
            setDiffLoading(false);
          }
        };
        void loadDiff();
      } else {
        openFile(filePath, startLine ?? 1);
      }
    },
    [graph, repoId, sessionId, changeDetails, openFile],
  );

  if (loading) {
    return <div className="agentrun-center">正在加载影响面数据…</div>;
  }
  if (error) {
    return <div className="agentrun-center agentrun-error">加载影响面失败：{error}</div>;
  }
  if (!graph) return null;

  if (graph.changedFiles.length === 0) {
    return (
      <div className="agentrun-center agentrun-empty-sub">
        本次执行没有文件改动
      </div>
    );
  }

  if (graph.changedSymbols.length === 0) {
    return (
      <div className="agentrun-center agentrun-empty-sub">
        <div>已检测到 {graph.changedFiles.length} 个改动文件</div>
        <div style={{ marginTop: 6, color: "var(--vs-fg-dim)", fontSize: 12 }}>
          代码符号索引尚未构建，请先构建仓库索引
        </div>
      </div>
    );
  }

  const upstreamCount = graph.upstream.nodes.length;
  const downstreamCount = graph.downstream.nodes.length;

  return (
    <div style={{ display: "flex", flexDirection: "column", height: "100%", minHeight: 0 }}>
      {/* 顶部摘要条 */}
      <div className="agentrun-summary">
        <span className="agentrun-summary-strong">
          {graph.changedFiles.length} 个文件改动
        </span>
        <span className="agentrun-summary-dot">·</span>
        <span style={{ color: COLOR_UPSTREAM }}>上游 {upstreamCount} 个调用方</span>
        <span className="agentrun-summary-dot">·</span>
        <span style={{ color: COLOR_DOWNSTREAM }}>下游 {downstreamCount} 个被调方</span>
        {graph.truncated && (
          <>
            <span className="agentrun-summary-dot">·</span>
            <span style={{ color: "#f48771", fontSize: 11 }}>（已截断，图较大）</span>
          </>
        )}
      </div>

      {/* ReactFlow 图 */}
      <div style={{ flex: 1, minHeight: 0, position: "relative", background: "var(--vs-bg)" }}>
        <ReactFlow
          nodes={rfNodes}
          edges={rfEdges}
          onNodeClick={onNodeClick}
          fitView
          minZoom={0.15}
          nodesDraggable={false}
          nodesConnectable={false}
        >
          <Background color="#30363d" gap={16} />
          <Controls showInteractive={false} />
        </ReactFlow>
      </div>

      {/* Diff 模态框 */}
      {diff && (
        <DiffModal
          filePath={diff.filePath}
          oldContent={diff.old}
          newContent={diff.neu}
          loading={diffLoading}
          onClose={() => setDiff(null)}
        />
      )}
    </div>
  );
}
