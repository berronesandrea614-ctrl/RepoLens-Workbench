import { useCallback, useEffect, useRef, useState } from "react";
import "./graph.css";
import "./requirement.css";
import {
  fetchRequirements,
  fetchRequirementGraph,
  deleteRequirement,
} from "../../api/requirementApi";
import { CodeGraph } from "../../types/graph";
import { Requirement } from "../../types/requirement";
import { initialVisible } from "./graphExpansion";
import { GraphCanvas } from "./GraphCanvas";
import { useWorkbench } from "../../state/workbenchStore";
import { RequirementInsightCard } from "../requirement/insight/RequirementInsightCard";

/** Sub-view within a selected requirement. */
type SubView = "insight" | "graph";

function shortDate(iso: string): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const now = Date.now();
  const diff = now - d.getTime();
  const min = 60 * 1000, hour = 60 * min, day = 24 * hour;
  if (diff < hour) return `${Math.max(1, Math.floor(diff / min))} 分钟前`;
  if (diff < day) return `${Math.floor(diff / hour)} 小时前`;
  if (diff < 7 * day) return `${Math.floor(diff / day)} 天前`;
  const mm = String(d.getMonth() + 1).padStart(2, "0");
  const dd = String(d.getDate()).padStart(2, "0");
  return `${d.getFullYear()}-${mm}-${dd}`;
}

export function RequirementGraphView() {
  // 仅在已打开仓库时挂载（EditorArea 冷启动渲染欢迎页），故 repoId 必非空。
  const repoId = useWorkbench((s) => s.repoId) as number;
  const openFile = useWorkbench((s) => s.openFile);

  // CC-6: consume activeRequirementId set by the external-changes → insight loop
  // or by the MCP show_requirement_viz tool.
  const activeRequirementId = useWorkbench((s) => s.activeRequirementId);
  const clearActiveRequirementId = useWorkbench((s) => s.clearActiveRequirementId);

  const [reqs, setReqs] = useState<Requirement[]>([]);
  const [listLoading, setListLoading] = useState(false);
  const [listError, setListError] = useState<string>();

  const [selected, setSelected] = useState<Requirement | null>(null);
  const [subView, setSubView] = useState<SubView>("insight");

  // Code subgraph state (only loaded on demand from insight card)
  const [graph, setGraph] = useState<CodeGraph | null>(null);
  const [graphLoading, setGraphLoading] = useState(false);
  const [graphError, setGraphError] = useState<string>();
  const [visible, setVisible] = useState<Set<string>>(() => new Set<string>());

  // Seed visible whenever a new graph arrives.
  useEffect(() => {
    if (graph) setVisible(initialVisible(graph, graph.rootId));
    else setVisible(new Set());
  }, [graph]);

  // 代际守卫：快速切换需求（或返回/切仓库）时，旧需求的子图不会覆盖新选中的需求。
  const reqGenRef = useRef(0);

  const loadList = useCallback(async () => {
    setListLoading(true);
    setListError(undefined);
    try {
      setReqs(await fetchRequirements(repoId));
    } catch (e: any) {
      setListError(e?.message ?? String(e));
    } finally {
      setListLoading(false);
    }
  }, [repoId]);

  useEffect(() => {
    // Reset selection when repo changes, then reload the list.
    reqGenRef.current += 1; // 放弃切仓库前在途的子图请求
    setSelected(null);
    setGraph(null);
    setSubView("insight");
    loadList();
  }, [repoId, loadList]);

  // CC-6: When activeRequirementId is set from outside (external-changes → insight loop
  // or MCP show_requirement_viz), automatically open that requirement's insight card.
  // We create a minimal Requirement placeholder so the insight card can render.
  useEffect(() => {
    if (activeRequirementId == null) return;
    reqGenRef.current += 1;
    setGraph(null);
    setGraphError(undefined);
    setSubView("insight");
    // Create a minimal stub so the insight card can load (it fetches by reqId anyway).
    setSelected({
      id: activeRequirementId,
      title: "Claude Code 改动归纳",
      summary: "",
      status: "SUMMARIZED",
      fileCount: 0,
      createdAt: new Date().toISOString(),
    });
    // Clear so a subsequent setView("requirements") doesn't re-trigger.
    clearActiveRequirementId();
    // Also refresh the list so the new requirement appears.
    void loadList();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeRequirementId]);

  /** Click a requirement in the list → open insight card. */
  const openRequirement = useCallback((req: Requirement) => {
    reqGenRef.current += 1; // invalidate any in-flight graph loads
    setSelected(req);
    setGraph(null);
    setGraphError(undefined);
    setSubView("insight");
  }, []);

  /** Open the call subgraph for the currently selected requirement. */
  const openSubgraph = useCallback(async () => {
    if (!selected) return;
    const myGen = ++reqGenRef.current;
    setGraph(null);
    setGraphError(undefined);
    setGraphLoading(true);
    setSubView("graph");
    try {
      const g = await fetchRequirementGraph(repoId, selected.id);
      if (reqGenRef.current !== myGen) return;
      setGraph(g);
    } catch (e: any) {
      if (reqGenRef.current !== myGen) return;
      setGraphError(e?.message ?? String(e));
    } finally {
      if (reqGenRef.current === myGen) setGraphLoading(false);
    }
  }, [repoId, selected]);

  /** Back from subgraph → insight card. */
  const backToInsight = useCallback(() => {
    reqGenRef.current += 1;
    setGraph(null);
    setGraphError(undefined);
    setSubView("insight");
  }, []);

  /** Back from insight (or subgraph) → requirement list. */
  const backToList = useCallback(() => {
    reqGenRef.current += 1;
    setSelected(null);
    setGraph(null);
    setGraphError(undefined);
    setSubView("insight");
  }, []);

  const remove = useCallback(async (e: React.MouseEvent, req: Requirement) => {
    e.stopPropagation();
    try {
      await deleteRequirement(repoId, req.id);
      setReqs((rs) => rs.filter((r) => r.id !== req.id));
      if (selected?.id === req.id) backToList();
    } catch (err: any) {
      setListError(err?.message ?? String(err));
    }
  }, [repoId, selected, backToList]);

  // ---- Requirement list (top level) ----
  if (!selected) {
    return (
      <div className="req-view">
        <div className="req-list-header">
          <span className="req-list-title">需求流</span>
          <button className="req-refresh-btn" onClick={loadList} disabled={listLoading}>
            {listLoading ? "刷新中…" : "刷新"}
          </button>
        </div>
        <div className="req-list">
          {listError && <div className="req-error">{listError}</div>}
          {!listError && listLoading && reqs.length === 0 && (
            <div className="req-empty">加载中…</div>
          )}
          {!listError && !listLoading && reqs.length === 0 && (
            <div className="req-empty">
              还没有需求——在 AI 会话里问一个有代码意图的问题，AI 会自动归纳成需求
            </div>
          )}
          {reqs.map((r) => (
            <div key={r.id} className="req-card" onClick={() => openRequirement(r)}>
              <div className="req-card-head">
                <span className="req-card-title">{r.title}</span>
                <button className="req-del-btn" title="删除需求" onClick={(e) => remove(e, r)}>
                  删除
                </button>
              </div>
              {r.summary && <div className="req-card-summary">{r.summary}</div>}
              <div className="req-card-meta">
                <span>{r.fileCount} 个文件</span>
                <span>{shortDate(r.createdAt)}</span>
              </div>
            </div>
          ))}
        </div>
      </div>
    );
  }

  // ---- Selected requirement → insight card ----
  if (subView === "insight") {
    return (
      <div className="req-view">
        <div className="req-breadcrumb">
          <button className="req-back-btn" onClick={backToList}>← 返回需求列表</button>
          <span className="req-crumb">需求：{selected.title}</span>
          <span className="req-crumb-sep">→</span>
          <span className="req-crumb">意图分析</span>
        </div>
        <div style={{ flex: 1, minHeight: 0, overflow: "hidden" }}>
          <RequirementInsightCard
            repoId={repoId}
            reqId={selected.id}
            reqTitle={selected.title}
            openFile={openFile}
            onViewSubgraph={() => void openSubgraph()}
          />
        </div>
      </div>
    );
  }

  // ---- Selected requirement → code-layer subgraph ----
  return (
    <div className="req-view">
      <div className="req-breadcrumb">
        <button className="req-back-btn" onClick={backToInsight}>← 返回意图分析</button>
        <span className="req-crumb">需求：{selected.title}</span>
        <span className="req-crumb-sep">→</span>
        <span className="req-crumb">代码流程</span>
        {graph && (
          <span className="hint">
            {graph.nodeCount} 节点 / {graph.edgeCount} 边{graph.truncated ? "（已截断）" : ""}
          </span>
        )}
      </div>
      {graphLoading && <div className="graph-canvas"><div className="req-empty">正在生成代码流程图…</div></div>}
      {!graphLoading && graphError && <div className="graph-canvas"><div className="req-error">{graphError}</div></div>}
      {!graphLoading && !graphError && graph && graph.nodes.length === 0 && (
        <div className="graph-canvas"><div className="req-empty">该需求还没有关联到具体代码符号</div></div>
      )}
      {!graphLoading && !graphError && graph && graph.nodes.length > 0 && (
        <GraphCanvas graph={graph} openFile={openFile} visible={visible} onSetVisible={setVisible} />
      )}
      <div className="graph-legend">
        <span style={{ color: "#8957e5" }}>Controller</span>
        <span style={{ color: "#1f6feb" }}>Service</span>
        <span style={{ color: "#238636" }}>Mapper</span>
        <span style={{ color: "#9e6a03" }}>Entity</span>
        <span style={{ color: "#484f58" }}>External（未解析）</span>
        <span style={{ color: "var(--vs-fg-dim)" }}>点击节点跳转到对应代码</span>
      </div>
    </div>
  );
}
