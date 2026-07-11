import { useCallback, useEffect, useState } from "react";
import { fetchTraceabilityMap, recomputeTraceability } from "../../api/traceabilityApi";
import { useWorkbench } from "../../state/workbenchStore";
import type { TraceEdge, TraceMapVO, TraceNode } from "./traceabilityTypes";
import {
  buildSummaryLabel,
  collectLayers,
  coverageColor,
  formatCoverage,
  isStaleEdge,
  nodeFlagLabel,
  partitionNodes,
} from "./traceabilityUtils";
import "./traceability.css";

/**
 * Feature C MVP: Spec↔Implementation Bidirectional Traceability View.
 *
 * 布局：顶部指标栏（覆盖率 + 孤岛/悬空/陈旧计数）+ 二部图表格（需求 | 符号）。
 * 二部图用 SVG 线段连接（轻量级，无需 ReactFlow 依赖）。
 * 点击需求行/符号行可跳转到正向/反向追溯抽屉（P1，本 MVP 仅显示静态表）。
 */
export function TraceabilityView() {
  const repoId = useWorkbench((s) => s.repoId);
  const [map, setMap] = useState<TraceMapVO | null>(null);
  const [loading, setLoading] = useState(false);
  const [recomputing, setRecomputing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!repoId) return;
    setLoading(true);
    setError(null);
    try {
      const data = await fetchTraceabilityMap(repoId);
      setMap(data);
    } catch (err: unknown) {
      setError(String(err));
    } finally {
      setLoading(false);
    }
  }, [repoId]);

  const handleRecompute = useCallback(async () => {
    if (!repoId || recomputing) return;
    setRecomputing(true);
    try {
      const data = await recomputeTraceability(repoId);
      setMap(data);
    } catch (err: unknown) {
      setError(String(err));
    } finally {
      setRecomputing(false);
    }
  }, [repoId, recomputing]);

  useEffect(() => {
    load();
  }, [load]);

  if (!repoId) {
    return (
      <div className="trace-empty">
        <span className="codicon codicon-git-compare" />
        <p>请先打开一个仓库</p>
      </div>
    );
  }

  if (loading && !map) {
    return <div className="trace-loading">加载追溯地图…</div>;
  }

  if (error) {
    return (
      <div className="trace-empty">
        <span className="codicon codicon-error" />
        <p>{error}</p>
        <button onClick={load} className="trace-btn">重试</button>
      </div>
    );
  }

  if (!map) return null;

  const { reqNodes, symNodes } = partitionNodes(map.nodes);
  const layers = collectLayers(symNodes);
  const color = coverageColor(map.metrics.coverage);

  return (
    <div className="trace-view">
      {/* ── 顶部指标栏 ── */}
      <div className="trace-toolbar">
        <div className={`trace-coverage trace-coverage--${color}`}>
          <span className="trace-coverage-value">{formatCoverage(map.metrics.coverage)}</span>
          <span className="trace-coverage-label">覆盖率</span>
        </div>
        <div className="trace-summary">{buildSummaryLabel(map.metrics)}</div>
        {map.degraded && (
          <div className="trace-degraded-badge" title="向量/LLM 不可用，仅 DECLARED links，结果可能低估">
            <span className="codicon codicon-warning" /> 降级模式
          </div>
        )}
        <div className="trace-toolbar-spacer" />
        <button
          className="trace-btn"
          onClick={handleRecompute}
          disabled={recomputing}
          title="强制重算追溯地图"
        >
          {recomputing ? "计算中…" : "重算"}
        </button>
      </div>

      {/* ── 主体：二部图 ── */}
      <div className="trace-body">
        {/* 左侧：需求列 */}
        <div className="trace-col trace-col--req">
          <div className="trace-col-header">需求 ({reqNodes.length})</div>
          {reqNodes.length === 0 && <div className="trace-col-empty">无需求</div>}
          {reqNodes.map((n) => (
            <TraceNodeRow key={n.id} node={n} />
          ))}
        </div>

        {/* 中间：SVG 连线（轻量实现：仅展示边 label 列表）*/}
        <div className="trace-col trace-col--edges">
          <div className="trace-col-header">链接 ({map.edges.length})</div>
          {map.edges.length === 0 && <div className="trace-col-empty">无链接</div>}
          {map.edges.map((e, i) => (
            <TraceEdgeRow key={i} edge={e} />
          ))}
        </div>

        {/* 右侧：符号列（按层分组）*/}
        <div className="trace-col trace-col--sym">
          <div className="trace-col-header">实现符号 ({symNodes.length})</div>
          {symNodes.length === 0 && (
            <div className="trace-col-empty" style={{ lineHeight: 1.7, textAlign: "left", padding: "10px 12px" }}>
              无实现符号。追溯把<b style={{ color: "#e3b341" }}>需求</b>连到代码里的符号；
              没有符号或没有需求时，需求会标为 <b>dangling（悬空）</b>。<br />
              常见原因：① 仓库<b style={{ color: "#e3b341" }}>尚未索引</b>（点右上「重算」）；
              ② <b style={{ color: "#e3b341" }}>尚未定义需求</b>——追溯需先有需求再自动连到代码符号
              （符号已支持 Java 及 TS/JS/Python/Go/Rust/C#/Ruby）。
            </div>
          )}
          {layers.length > 0
            ? layers.map((layer) => (
                <div key={layer} className="trace-layer-group">
                  <div className="trace-layer-label">{layer}</div>
                  {symNodes
                    .filter((n) => n.layer === layer)
                    .map((n) => (
                      <TraceNodeRow key={n.id} node={n} />
                    ))}
                </div>
              ))
            : symNodes.map((n) => <TraceNodeRow key={n.id} node={n} />)}
        </div>
      </div>
    </div>
  );
}

function TraceNodeRow({ node }: { node: TraceNode }) {
  const flagLabel = nodeFlagLabel(node.flag);
  // dangling：该需求没有连到任何实现符号（未落地/未索引/非 Java）。悬停给出可读解释，
  // 避免用户以为「点了没反应」——本 MVP 尚未实现点击下钻抽屉。
  const flagTitle =
    node.flag === "dangling"
      ? "悬空：此需求未连到任何实现符号。可能未落地实现，或仓库尚未索引（符号已支持 Java 及 TS/JS/Python/Go/Rust/C#/Ruby）。"
      : node.flag === "orphan"
        ? "孤岛：此符号未被任何需求覆盖。"
        : node.flag === "stale"
          ? "陈旧：关联的代码或需求已变更，链接可能失效。"
          : undefined;
  return (
    <div className={`trace-node-row trace-node-row--${node.nodeType}${node.flag ? ` trace-node-row--${node.flag}` : ""}`}>
      <span className="trace-node-label" title={node.id}>{node.label}</span>
      {flagLabel && <span className="trace-node-flag" title={flagTitle}>{flagLabel}</span>}
    </div>
  );
}

function TraceEdgeRow({ edge }: { edge: TraceEdge }) {
  const stale = isStaleEdge(edge);
  return (
    <div className={`trace-edge-row${stale ? " trace-edge-row--stale" : ""}`}>
      <span className="trace-edge-src" title={edge.source}>{edge.source}</span>
      <span className="trace-edge-arrow">{stale ? "⤳" : "→"}</span>
      <span className="trace-edge-tgt" title={edge.target}>{edge.target}</span>
      <span className="trace-edge-type">{edge.linkType}</span>
      <span className="trace-edge-conf">{Math.round(edge.confidence * 100)}%</span>
    </div>
  );
}
