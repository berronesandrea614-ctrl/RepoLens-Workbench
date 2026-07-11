import { useCallback, useMemo, useState } from "react";
import ReactFlow, {
  Background,
  Controls,
  Edge,
  Node,
  NodeTypes,
} from "reactflow";
import dagre from "dagre";
import "reactflow/dist/style.css";
import "./branch.css";

import { fanout, fetchBranchGraph, selectBranch, BranchGraph, BranchNode } from "../../api/branchApi";
import { useWorkbench } from "../../state/workbenchStore";
import { BranchNodeComponent, BranchNodeFlowData } from "./BranchNode";
import { clampVariantCount } from "./branchHelpers";

// ── ReactFlow nodeTypes (stable ref — defined outside component) ──
const nodeTypes: NodeTypes = { branch: BranchNodeComponent };

// ── Dagre layout ─────────────────────────────────────────────────
const NODE_W = 240;
const NODE_H = 120;

function buildLayout(nodes: BranchNode[]): { rfNodes: Node[]; rfEdges: Edge[] } {
  const g = new dagre.graphlib.Graph();
  g.setGraph({ rankdir: "TB", nodesep: 40, ranksep: 60 });
  g.setDefaultEdgeLabel(() => ({}));

  for (const n of nodes) {
    g.setNode(n.id, { width: NODE_W, height: NODE_H });
  }

  const edgeSet = new Set<string>();
  for (const n of nodes) {
    if (n.parentBranchId) {
      // parentBranchId is the branchId (string), id is also a string
      // Find parent node by branchId or id
      const parentNode = nodes.find(
        (p) => p.id === n.parentBranchId || p.branchId === n.parentBranchId,
      );
      if (parentNode && g.hasNode(parentNode.id) && g.hasNode(n.id)) {
        const eid = `${parentNode.id}->${n.id}`;
        if (!edgeSet.has(eid)) {
          edgeSet.add(eid);
          g.setEdge(parentNode.id, n.id);
        }
      }
    }
  }

  dagre.layout(g);

  const rfNodes: Node[] = nodes.map((n) => {
    const pos = g.node(n.id) ?? { x: 0, y: 0 };
    const data: BranchNodeFlowData = n;
    return {
      id: n.id,
      type: "branch",
      position: { x: (pos.x ?? 0) - NODE_W / 2, y: (pos.y ?? 0) - NODE_H / 2 },
      data,
    };
  });

  const rfEdges: Edge[] = [];
  for (const eid of edgeSet) {
    const [src, tgt] = eid.split("->");
    if (src && tgt) {
      rfEdges.push({
        id: eid,
        source: src,
        target: tgt,
        style: { stroke: "#3d444d", strokeWidth: 1.5 },
        animated: false,
      });
    }
  }

  return { rfNodes, rfEdges };
}

// ── Main component ────────────────────────────────────────────────
export function BranchGraphView() {
  const repoId = useWorkbench((s) => s.repoId);
  const activeSessionId = useWorkbench((s) => s.activeSessionId);

  // Local session ID: prefill from workbench if available
  const [sessionIdStr, setSessionIdStr] = useState<string>(
    activeSessionId != null ? String(activeSessionId) : "",
  );
  const [question, setQuestion] = useState("");
  const [variantCount, setVariantCount] = useState(3);
  const [graph, setGraph] = useState<BranchGraph | null>(null);
  const [loading, setLoading] = useState(false);
  const [loadingMsg, setLoadingMsg] = useState("");
  const [error, setError] = useState<string>();
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [adopting, setAdopting] = useState(false);
  const [appliedFiles, setAppliedFiles] = useState<string[] | null>(null);

  const sessionId = sessionIdStr ? Number(sessionIdStr) : null;
  const clampedCount = clampVariantCount(variantCount);

  // ── Trigger fanout ────────────────────────────────────────────
  const handleFanout = useCallback(async () => {
    if (repoId == null || sessionId == null || !question.trim()) return;
    setLoading(true);
    setError(undefined);
    setGraph(null);
    setSelectedNodeId(null);
    setAppliedFiles(null);
    setLoadingMsg(`并行生成 ${clampedCount} 个变体…可能较慢`);
    try {
      const g = await fanout(repoId, sessionId, question.trim(), clampedCount);
      setGraph(g);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
      setLoadingMsg("");
    }
  }, [repoId, sessionId, question, clampedCount]);

  // ── Refresh graph from server ─────────────────────────────────
  const handleRefresh = useCallback(async () => {
    if (repoId == null || sessionId == null) return;
    try {
      const g = await fetchBranchGraph(repoId, sessionId);
      setGraph(g);
    } catch {
      // best-effort
    }
  }, [repoId, sessionId]);

  // ── Select / adopt a branch ───────────────────────────────────
  const handleAdopt = useCallback(async () => {
    if (repoId == null || sessionId == null || !selectedNodeId || !graph) return;
    const node = graph.nodes.find((n) => n.id === selectedNodeId);
    if (!node) return;
    setAdopting(true);
    try {
      const changes = await selectBranch(repoId, node.branchId, sessionId, true);
      setAppliedFiles(changes.map((c) => c.filePath));
      // Refresh to get DISCARDED states from server
      const updated = await fetchBranchGraph(repoId, sessionId);
      setGraph(updated);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setAdopting(false);
    }
  }, [repoId, sessionId, selectedNodeId, graph]);

  // ── ReactFlow layout ──────────────────────────────────────────
  const { rfNodes, rfEdges } = useMemo(() => {
    if (!graph || graph.nodes.length === 0) return { rfNodes: [], rfEdges: [] };
    return buildLayout(graph.nodes);
  }, [graph]);

  const onNodeClick = useCallback((_: React.MouseEvent, node: Node) => {
    setSelectedNodeId(node.id);
    setAppliedFiles(null);
  }, []);

  const selectedNode = selectedNodeId
    ? graph?.nodes.find((n) => n.id === selectedNodeId)
    : null;

  const canAdopt =
    selectedNode != null &&
    selectedNode.status !== "SELECTED" &&
    selectedNode.status !== "DISCARDED" &&
    !adopting;

  // ── Empty / no-repo ───────────────────────────────────────────
  if (repoId == null) {
    return (
      <div className="branch-view">
        <div className="branch-center">
          <span className="codicon codicon-git-branch branch-center-icon" />
          <div>请先打开一个仓库</div>
        </div>
      </div>
    );
  }

  return (
    <div className="branch-view">
      {/* ── Toolbar ── */}
      <div className="branch-toolbar">
        <span className="branch-toolbar-label">方案分支</span>

        <div className="branch-session-badge">
          <span>Session</span>
          <input
            className="branch-session-input"
            type="number"
            placeholder="ID"
            value={sessionIdStr}
            min={1}
            onChange={(e) => setSessionIdStr(e.target.value)}
          />
        </div>

        <input
          className="branch-toolbar-input"
          type="text"
          placeholder="输入问题生成方案变体…"
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Enter" && !loading) void handleFanout(); }}
          disabled={loading}
        />

        <select
          className="branch-toolbar-select"
          value={clampedCount}
          onChange={(e) => setVariantCount(Number(e.target.value))}
          disabled={loading}
        >
          <option value={2}>2 变体</option>
          <option value={3}>3 变体</option>
          <option value={4}>4 变体</option>
        </select>

        <button
          className="branch-toolbar-btn"
          onClick={() => void handleFanout()}
          disabled={loading || !question.trim() || sessionId == null}
        >
          产生变体
        </button>

        {graph && (
          <button
            className="branch-toolbar-btn"
            style={{ background: "#21262d" }}
            onClick={() => void handleRefresh()}
            disabled={loading}
          >
            刷新
          </button>
        )}
      </div>

      {/* ── Disclaimer strip ── */}
      <div className="branch-status-strip">
        <span className="branch-warn-tag">⚠ 未落盘变体无法真实测试，confidence 为静态自评</span>
        {graph && (
          <span style={{ marginLeft: 12 }}>
            {graph.nodes.length} 个变体 · Session {graph.sessionId}
          </span>
        )}
      </div>

      {/* ── Error ── */}
      {error && (
        <div className="branch-center branch-error">
          <span className="codicon codicon-error" style={{ fontSize: 24 }} />
          <div>{error}</div>
        </div>
      )}

      {/* ── Loading ── */}
      {!error && loading && (
        <div className="branch-center">
          <span className="codicon codicon-loading codicon-modifier-spin" style={{ fontSize: 28 }} />
          <div>{loadingMsg}</div>
        </div>
      )}

      {/* ── Empty state ── */}
      {!error && !loading && !graph && (
        <div className="branch-center">
          <span className="codicon codicon-git-branch branch-center-icon" />
          <div style={{ fontWeight: 600, color: "var(--vs-fg)" }}>输入问题生成方案变体</div>
          <div style={{ fontSize: 12 }}>
            填写 Session ID 和问题，选择变体数，点击「产生变体」
          </div>
        </div>
      )}

      {/* ── Graph + detail panel ── */}
      {!error && !loading && graph && (
        <div style={{ flex: 1, minHeight: 0, position: "relative", display: "flex" }}>
          <div className="branch-canvas">
            <ReactFlow
              nodes={rfNodes}
              edges={rfEdges}
              nodeTypes={nodeTypes}
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

          {/* ── Detail panel ── */}
          {selectedNode && (
            <div className="branch-detail">
              <button
                className="branch-detail-close"
                onClick={() => { setSelectedNodeId(null); setAppliedFiles(null); }}
              >
                ×
              </button>

              <div className="branch-detail-label">{selectedNode.label}</div>
              <div style={{ fontSize: 10, color: "var(--vs-fg-dim)" }}>
                变体 #{selectedNode.variantIndex} · {selectedNode.status}
                {selectedNode.degraded && (
                  <span style={{ color: "#f0883e", marginLeft: 6 }}>⚠ 自评</span>
                )}
              </div>

              {selectedNode.approach && (
                <div className="branch-detail-approach">{selectedNode.approach}</div>
              )}

              {selectedNode.strategyHint && (
                <div style={{ fontSize: 11, color: "#58a6ff" }}>
                  策略：{selectedNode.strategyHint}
                </div>
              )}

              <div style={{ display: "flex", flexDirection: "column", gap: 4, fontSize: 12 }}>
                <span>改动文件：{selectedNode.metrics.filesChanged}</span>
                <span>影响范围：{selectedNode.metrics.blastRadiusSize}</span>
                <span>债务变化：{selectedNode.metrics.debtDelta > 0 ? "+" : ""}{selectedNode.metrics.debtDelta}</span>
                <span>置信度：{selectedNode.metrics.confidence}%</span>
                {selectedNode.metrics.verified && (
                  <span style={{ color: "#3fb950" }}>✓ 已验证</span>
                )}
              </div>

              {appliedFiles && (
                <div className="branch-detail-applied">
                  ✓ 已应用 {appliedFiles.length} 个文件
                </div>
              )}

              <button
                className="branch-detail-adopt-btn"
                onClick={() => void handleAdopt()}
                disabled={!canAdopt}
              >
                {adopting ? "采纳中…" : "采纳此方案"}
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
