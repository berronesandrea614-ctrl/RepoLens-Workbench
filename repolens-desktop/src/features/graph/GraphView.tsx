import { useCallback, useEffect, useRef, useState } from "react";
import "./graph.css";
import { fetchGraph, explainGraph } from "../../api/graphApi";
import { searchSymbols } from "../../api/symbolApi";
import { CodeGraph, SymbolHit } from "../../types/graph";
import { neighborhood, pathFromRoot, TraceResult } from "./graphTrace";
import { initialVisible } from "./graphExpansion";
import { GraphCanvas } from "./GraphCanvas";
import { useWorkbench } from "../../state/workbenchStore";

export function GraphView() {
  // 仅在已打开仓库时挂载（EditorArea 冷启动渲染欢迎页），故 repoId 必非空。
  const repoId = useWorkbench((s) => s.repoId) as number;
  const openFile = useWorkbench((s) => s.openFile);
  const setView = useWorkbench((s) => s.setView);

  const [keyword, setKeyword] = useState("");
  const [hits, setHits] = useState<SymbolHit[]>([]);
  // 搜索完成但零命中的标记：用于给出「无匹配符号」诚实反馈，
  // 避免用户输入后下拉框一片空白、不知发生了什么（仓库未索引或非 Java 项目常见）。
  const [noHit, setNoHit] = useState(false);
  const [rootId, setRootId] = useState<number | null>(null);
  const [rootLabel, setRootLabel] = useState("");
  const [direction, setDirection] = useState<"callees" | "callers">("callees");
  const [depth, setDepth] = useState(2);
  const [graph, setGraph] = useState<CodeGraph | null>(null);
  const [error, setError] = useState<string>();

  // T4: flow-tracing interactions (now applied on the visible subgraph).
  const [traceMode, setTraceMode] = useState(false);
  const [highlight, setHighlight] = useState<TraceResult | null>(null);
  const [focus, setFocus] = useState<TraceResult | null>(null);

  // I4: visible subgraph state (lifted from GraphCanvas so explain can use it).
  const [visible, setVisible] = useState<Set<string>>(() => new Set<string>());

  // T5: graph flow explanation panel.
  const [explainOpen, setExplainOpen] = useState(false);
  const [explainLoading, setExplainLoading] = useState(false);
  const [explainText, setExplainText] = useState("");
  const [explainError, setExplainError] = useState<string>();

  // 符号搜索：300ms 防抖 + 代际守卫，旧查询结果不覆盖新查询。
  const searchGenRef = useRef(0);
  const searchTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(
    () => () => {
      if (searchTimerRef.current) clearTimeout(searchTimerRef.current);
      searchGenRef.current += 1; // 卸载后放弃在途结果
    },
    [],
  );

  function doSearch(kw: string) {
    setKeyword(kw);
    setNoHit(false);
    const myGen = ++searchGenRef.current;
    if (searchTimerRef.current) clearTimeout(searchTimerRef.current);
    if (kw.trim().length < 2) { setHits([]); return; }
    searchTimerRef.current = setTimeout(async () => {
      try {
        const r = await searchSymbols(repoId, kw.trim());
        if (searchGenRef.current === myGen) { setHits(r); setNoHit(r.length === 0); }
      } catch {
        if (searchGenRef.current === myGen) { setHits([]); setNoHit(true); }
      }
    }, 300);
  }

  function pick(hit: SymbolHit) {
    setRootId(hit.id);
    setRootLabel(`${hit.className ?? ""}${hit.methodName ? "#" + hit.methodName : ""}`);
    setHits([]);
    setNoHit(false);
    setKeyword("");
  }

  useEffect(() => {
    if (rootId == null) return;
    setVisible(new Set()); // reset visible before new graph arrives

    let ignore = false;

    const load = async () => {
      setError(undefined);
      // Reset all tracing state whenever a fresh graph is loaded.
      setTraceMode(false);
      setHighlight(null);
      setFocus(null);
      try {
        const g = await fetchGraph(repoId, rootId, direction, depth, 0);
        if (ignore) return;
        setGraph(g);
      } catch (e: any) {
        if (ignore) return;
        setError(e?.message ?? String(e));
      }
    };

    load();

    return () => { ignore = true; };
  }, [repoId, rootId, direction, depth]);

  // Seed visible from graph once it arrives.
  useEffect(() => {
    if (graph) setVisible(initialVisible(graph, graph.rootId));
  }, [graph]);

  const onNodeTrace = useCallback((nodeId: string) => {
    if (graph) setHighlight(pathFromRoot(graph.edges, graph.rootId, nodeId));
  }, [graph]);

  const onPaneClick = useCallback(() => setHighlight(null), []);

  const toggleTrace = useCallback(() => {
    setTraceMode((on) => {
      if (on) setHighlight(null); // leaving trace mode clears the highlight
      return !on;
    });
  }, []);

  const focusOn = useCallback((nodeId: string) => {
    if (!graph) return;
    setFocus(neighborhood(graph.edges, nodeId));
    setHighlight(null);
  }, [graph]);

  const restoreFocus = useCallback(() => setFocus(null), []);

  const explain = useCallback(async () => {
    if (!graph) return;
    setExplainOpen(true);
    setExplainLoading(true);
    setExplainError(undefined);
    setExplainText("");
    try {
      const byId = new Map(graph.nodes.map((n) => [n.id, n]));
      const rootNode = byId.get(graph.rootId);
      // Use visible subgraph instead of a blind slice of the full graph.
      const visibleNodes = graph.nodes.filter((n) => visible.has(n.id));
      const visibleEdges = graph.edges.filter(
        (e) => visible.has(e.source) && visible.has(e.target),
      );
      const nodes = visibleNodes.map((n) => `${n.label} [${n.layer ?? n.symbolType}]`);
      const edges = visibleEdges.map((e) => {
        const s = byId.get(e.source)?.label ?? e.source;
        const t = byId.get(e.target)?.label ?? e.target;
        return `${s} -> ${t}`;
      });
      const text = await explainGraph(repoId, {
        rootLabel: rootNode?.label ?? rootLabel ?? "",
        nodes,
        edges,
      });
      setExplainText(text);
    } catch (e: any) {
      setExplainError(e?.message ?? String(e));
    } finally {
      setExplainLoading(false);
    }
  }, [graph, repoId, rootLabel, visible]);

  return (
    <div className="graph-view">
      <div className="graph-toolbar">
        <div className="graph-suggest">
          <input placeholder="搜索符号作为根（类/方法名）…" value={keyword}
            onChange={(e) => doSearch(e.target.value)} style={{ width: 220 }} />
          {hits.length > 0 && (
            <div className="graph-suggest-list">
              {hits.map((h) => (
                <div key={h.id} onClick={() => pick(h)}>
                  <span style={{ color: "#4daafc" }}>{h.symbolType}</span>{" "}
                  {h.className}{h.methodName ? `#${h.methodName}` : ""}
                </div>
              ))}
            </div>
          )}
          {noHit && hits.length === 0 && keyword.trim().length >= 2 && (
            <div className="graph-suggest-list graph-suggest-empty">
              <div className="graph-suggest-empty-title">未匹配到符号「{keyword.trim()}」</div>
              <div className="graph-suggest-empty-hint">
                该仓库可能<b>尚未索引</b>，或是<b>非 Java 项目</b>——当前符号级分析（调用图/依赖）仅支持 Java。
                请对该仓库重新索引，或切换到已索引的 Java 仓库再试。
              </div>
            </div>
          )}
        </div>
        <span className="hint">{rootLabel || "未选择根符号"}</span>
        <select value={direction} onChange={(e) => setDirection(e.target.value as any)}>
          <option value="callees">下游被调用</option>
          <option value="callers">上游调用者</option>
        </select>
        <select value={depth} onChange={(e) => setDepth(Number(e.target.value))}>
          <option value={1}>1 层</option>
          <option value={2}>2 层</option>
          <option value={3}>3 层</option>
          <option value={4}>4 层</option>
        </select>
        {graph && <span className="hint">{graph.nodeCount} 节点 / {graph.edgeCount} 边{graph.truncated ? "（已截断）" : ""}</span>}
        {graph && (
          <button
            className={traceMode ? "graph-trace-btn active" : "graph-trace-btn"}
            onClick={toggleTrace}
            title="开启后，点击节点高亮它到根符号的调用链路（不再跳转文件）"
          >
            {traceMode ? "追踪模式：开" : "追踪模式"}
          </button>
        )}
        {focus && (
          <button className="graph-restore-btn" onClick={restoreFocus}>还原</button>
        )}
        <button
          className="graph-explain-btn"
          onClick={explain}
          disabled={!graph || explainLoading}
          title="调用大模型，根据当前图生成一段自然语言的流程解说"
        >
          {explainLoading ? "解说中…" : "解说本图"}
        </button>
      </div>
      {error && <div style={{ color: "#f48771", padding: 8 }}>{error}</div>}
      {graph && graph.nodes.length <= 1 && graph.edges.length === 0 && (
        <div className="hint" style={{ padding: 8 }}>
          该符号没有依赖记录（API 符号的调用边通常挂在同名 METHOD 符号上，试试选 METHOD 类型的根）。
        </div>
      )}
      {rootId == null || !graph ? (
        <div className="graph-canvas">
          <div style={{ color: "var(--vs-fg-dim)", padding: 20 }}>搜索并选择一个符号，查看它的数据流转链路</div>
        </div>
      ) : (
        <GraphCanvas
          graph={graph}
          openFile={(path, line) => { openFile(path, line); setView("editor"); }}
          visible={visible}
          onSetVisible={setVisible}
          traceMode={traceMode}
          onNodeTrace={onNodeTrace}
          highlight={highlight}
          onPaneClick={onPaneClick}
          focus={focus}
          onFocusNode={focusOn}
          onRestoreFocus={restoreFocus}
        />
      )}
      {explainOpen && (
        <div className="graph-explain-panel">
          <div className="graph-explain-head">
            <span className="graph-explain-title">流程解说{rootLabel ? `：${rootLabel}` : ""}</span>
            <button className="graph-explain-close" onClick={() => setExplainOpen(false)} title="关闭">
              ×
            </button>
          </div>
          <div className="graph-explain-body">
            {explainLoading && <div className="graph-explain-status">正在生成流程解说…</div>}
            {!explainLoading && explainError && (
              <div className="graph-explain-error">{explainError}</div>
            )}
            {!explainLoading && !explainError && explainText && (
              <div className="graph-explain-text">{explainText}</div>
            )}
            {!explainLoading && !explainError && !explainText && (
              <div className="graph-explain-status">暂无解说内容</div>
            )}
          </div>
        </div>
      )}
      <div className="graph-legend">
        <span style={{ color: "#8957e5" }}>Controller</span>
        <span style={{ color: "#1f6feb" }}>Service</span>
        <span style={{ color: "#238636" }}>Mapper</span>
        <span style={{ color: "#9e6a03" }}>Entity</span>
        <span style={{ color: "#484f58" }}>External（未解析）</span>
        <span style={{ color: "var(--vs-fg-dim)" }}>实线粗=高置信 · 虚线=低置信(&lt;0.9) · 边标签=返回类型</span>
      </div>
    </div>
  );
}
