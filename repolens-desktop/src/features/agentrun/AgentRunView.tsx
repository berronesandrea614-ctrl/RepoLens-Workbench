import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Panel, PanelGroup, PanelResizeHandle } from "react-resizable-panels";
import ReactFlow, { Background, Controls, Node, Edge, Position } from "reactflow";
import dagre from "dagre";
import "reactflow/dist/style.css";
import "./agentrun.css";
import { listAgentRuns, fetchAgentRunTrace } from "../../api/agentRunApi";
import { AgentRunMeta, AgentRunStep, AgentRunTrace } from "../../types/agentRun";
import { useWorkbench } from "../../state/workbenchStore";
import { BlastRadiusView } from "./BlastRadiusView";

// ── step 类型 → 图标 / 颜色 / 中文名 ─────────────────────────────
const STEP_META: Record<
  AgentRunStep["type"],
  { icon: string; color: string; label: string }
> = {
  THINK: { icon: "codicon-comment", color: "#a074c4", label: "思考" },
  TOOL: { icon: "codicon-tools", color: "#4daafc", label: "工具" },
  WRITE: { icon: "codicon-edit", color: "#e3b341", label: "写入" },
};

function modeLabel(mode: string): string {
  return mode === "code" ? "编码" : mode === "ask" ? "问答" : mode;
}

/** 相对时间格式，与 MemoryPanel.tsx 保持一致。 */
function shortDate(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const now = Date.now();
  const diff = now - d.getTime();
  const min = Math.floor(diff / 60000);
  if (min < 1) return "刚刚";
  if (min < 60) return `${min} 分钟前`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return `${hr} 小时前`;
  const day = Math.floor(hr / 24);
  if (day < 7) return `${day} 天前`;
  return d.toLocaleDateString();
}

function stepSummary(s: AgentRunStep): string {
  if (s.type === "THINK") return s.thought ?? "（思考）";
  const args = s.toolArgs ? `(${truncate(s.toolArgs, 60)})` : "";
  return `${s.toolName ?? s.type}${args}`;
}

function truncate(text: string, n: number): string {
  const t = text.replace(/\s+/g, " ").trim();
  return t.length > n ? t.slice(0, n) + "…" : t;
}

/**
 * 从工具参数里抽取「作用目标」（文件/模式/符号/查询），用于让轨迹节点显示
 * 「工具 · 目标」而非只有工具名——用户一眼能看懂每步在对什么做什么。
 * 防御式：toolArgs 可能非 JSON 或字段变动，全程 try/catch，取不到就返回空串。
 */
function stepTarget(s: AgentRunStep): string {
  if (!s.toolArgs) return "";
  try {
    const obj = JSON.parse(s.toolArgs);
    const raw =
      obj.filePath ?? obj.path ?? obj.pattern ?? obj.query ??
      obj.symbol ?? obj.className ?? obj.methodName ?? obj.name ?? obj.keyword;
    if (raw == null) return "";
    const str = String(raw);
    // 路径取文件名，避免过长
    const base = str.includes("/") ? str.slice(str.lastIndexOf("/") + 1) : str;
    return truncate(base, 22);
  } catch {
    return "";
  }
}

// ── DAG 布局（dagre，上到下）───────────────────────────────────
const DAG_W = 176;
const DAG_H = 46;

function layoutStepsBase(steps: AgentRunStep[]): { nodes: Node[]; edges: Edge[] } {
  const g = new dagre.graphlib.Graph();
  g.setGraph({ rankdir: "TB", nodesep: 28, ranksep: 40 });
  g.setDefaultEdgeLabel(() => ({}));
  steps.forEach((s) => g.setNode(String(s.stepIndex), { width: DAG_W, height: DAG_H }));
  for (let i = 0; i < steps.length - 1; i++) {
    g.setEdge(String(steps[i].stepIndex), String(steps[i + 1].stepIndex));
  }
  dagre.layout(g);

  const nodes: Node[] = steps.map((s) => {
    const meta = STEP_META[s.type];
    const pos = g.node(String(s.stepIndex));
    const target = stepTarget(s);
    // 标签：#序号 工具名 · 目标（无工具名回落 meta.label；无目标则省略）
    const head = s.toolName ?? meta.label;
    const label = `#${s.stepIndex} ${head}${target ? " · " + target : ""}`;
    return {
      id: String(s.stepIndex),
      position: { x: (pos?.x ?? 0) - DAG_W / 2, y: (pos?.y ?? 0) - DAG_H / 2 },
      data: { label },
      sourcePosition: Position.Bottom,
      targetPosition: Position.Top,
      style: {
        width: DAG_W,
        background: s.type === "WRITE" ? "#2b2411" : "#161b22",
        color: "#e6edf3",
        border: `1px solid ${meta.color}`,
        borderLeft: `4px solid ${meta.color}`,
        borderRadius: 6,
        fontSize: 11,
        padding: "6px 8px",
      },
    };
  });

  const edges: Edge[] = [];
  for (let i = 0; i < steps.length - 1; i++) {
    edges.push({
      id: `e${steps[i].stepIndex}-${steps[i + 1].stepIndex}`,
      source: String(steps[i].stepIndex),
      target: String(steps[i + 1].stepIndex),
      style: { stroke: "#4daafc", strokeWidth: 1.6 },
      animated: false,
    });
  }
  return { nodes, edges };
}

function applyStepSelection(
  base: { nodes: Node[]; edges: Edge[] },
  selectedIndex: number | null,
): { nodes: Node[]; edges: Edge[] } {
  if (selectedIndex == null) return base;
  return {
    nodes: base.nodes.map((n) =>
      n.id === String(selectedIndex)
        ? {
            ...n,
            style: {
              ...n.style,
              boxShadow: `0 0 0 2px #4daafc, 0 0 10px rgba(77,170,252,0.5)`,
            },
          }
        : n,
    ),
    edges: base.edges,
  };
}

// ── 主视图 ─────────────────────────────────────────────────────
export function AgentRunView() {
  // 仅在已打开仓库时挂载（EditorArea 冷启动渲染欢迎页），故 repoId 必非空。
  const repoId = useWorkbench((s) => s.repoId) as number;
  const openFile = useWorkbench((s) => s.openFile);
  const activeAgentRunId = useWorkbench((s) => s.activeAgentRunId);

  const [runs, setRuns] = useState<AgentRunMeta[] | null>(null);
  const [runsError, setRunsError] = useState<string>();
  const [selectedRunId, setSelectedRunId] = useState<number | null>(null);

  const [trace, setTrace] = useState<AgentRunTrace | null>(null);
  const [traceLoading, setTraceLoading] = useState(false);
  const [traceError, setTraceError] = useState<string>();

  const [selectedStep, setSelectedStep] = useState<number | null>(null);
  const [expanded, setExpanded] = useState<Set<number>>(new Set());
  const [activeTab, setActiveTab] = useState<"trace" | "blast">("trace");

  const stepRefs = useRef<Map<number, HTMLDivElement>>(new Map());

  // 拉取 run 列表。
  useEffect(() => {
    let ignore = false;
    setRuns(null);
    setRunsError(undefined);
    listAgentRuns(repoId)
      .then((r) => {
        if (ignore) return;
        setRuns(r);
        // 默认选中 activeAgentRunId（若存在于列表）否则最新一条。
        const preferred =
          activeAgentRunId != null && r.some((x) => x.id === activeAgentRunId)
            ? activeAgentRunId
            : r[0]?.id ?? null;
        setSelectedRunId(preferred);
      })
      .catch((e) => {
        if (!ignore) setRunsError(e?.message ?? String(e));
      });
    return () => {
      ignore = true;
    };
  }, [repoId, activeAgentRunId]);

  // 拉取选中 run 的轨迹。
  useEffect(() => {
    if (selectedRunId == null) {
      setTrace(null);
      return;
    }
    let ignore = false;
    setTraceLoading(true);
    setTraceError(undefined);
    setSelectedStep(null);
    setExpanded(new Set());
    setActiveTab("trace");
    fetchAgentRunTrace(repoId, selectedRunId)
      .then((t) => {
        if (ignore) return;
        setTrace(t);
      })
      .catch((e) => {
        if (!ignore) setTraceError(e?.message ?? String(e));
      })
      .finally(() => {
        if (!ignore) setTraceLoading(false);
      });
    return () => {
      ignore = true;
    };
  }, [repoId, selectedRunId]);

  const steps = trace?.steps ?? [];

  const selectStep = useCallback((stepIndex: number) => {
    setSelectedStep(stepIndex);
    const el = stepRefs.current.get(stepIndex);
    if (el) el.scrollIntoView({ behavior: "smooth", block: "center" });
  }, []);

  const toggleExpand = useCallback((stepIndex: number) => {
    setExpanded((prev) => {
      const n = new Set(prev);
      if (n.has(stepIndex)) n.delete(stepIndex);
      else n.add(stepIndex);
      return n;
    });
  }, []);

  const dagLayout = useMemo(() => layoutStepsBase(steps), [steps]);
  const dag = useMemo(() => applyStepSelection(dagLayout, selectedStep), [dagLayout, selectedStep]);

  const onDagNodeClick = useCallback(
    (_: React.MouseEvent, node: Node) => {
      const idx = Number(node.id);
      selectStep(idx);
    },
    [selectStep],
  );

  // ── 无仓库以外的空/错误态 ──
  if (runsError) {
    return (
      <div className="agentrun-view">
        <div className="agentrun-center agentrun-error">加载执行记录失败：{runsError}</div>
      </div>
    );
  }
  if (runs == null) {
    return (
      <div className="agentrun-view">
        <div className="agentrun-center">正在加载 Agent 执行记录…</div>
      </div>
    );
  }
  if (runs.length === 0) {
    return (
      <div className="agentrun-view">
        <div className="agentrun-center agentrun-empty">
          <span className="codicon codicon-pulse agentrun-empty-icon" />
          <div className="agentrun-empty-title">还没有 Agent 执行记录</div>
          <div className="agentrun-empty-sub">
            用编码模式让 AI 帮你改代码，这里会记录它的每一步思考与工具调用
          </div>
        </div>
      </div>
    );
  }

  const run = trace?.run;

  return (
    <div className="agentrun-view">
      {/* 左：run 列表 */}
      <div className="agentrun-runlist">
        <div className="agentrun-runlist-head">执行记录 ({runs.length})</div>
        <div className="agentrun-runlist-body">
          {runs.map((r) => (
            <div
              key={r.id}
              className={`agentrun-runrow ${r.id === selectedRunId ? "active" : ""}`}
              onClick={() => setSelectedRunId(r.id)}
              title={r.question}
            >
              <div className="agentrun-runrow-q">{r.question}</div>
              <div className="agentrun-runrow-meta">
                <span
                  className={`agentrun-mode-badge ${r.mode === "code" ? "code" : "ask"}`}
                >
                  {modeLabel(r.mode)}
                </span>
                <span className="agentrun-runrow-stat">{r.iterations} 迭代</span>
                <span className="agentrun-runrow-stat">{r.toolCalls} 工具</span>
              </div>
              <div className="agentrun-runrow-time">{r.createdAt ? shortDate(r.createdAt) : ""}</div>
            </div>
          ))}
        </div>
      </div>

      {/* 右：summary + 时间线 | DAG */}
      <div className="agentrun-main">
        {/* 标签页切换 */}
        {selectedRunId != null && (
          <div className="agentrun-tab-bar">
            <button
              className={`agentrun-tab ${activeTab === "trace" ? "active" : ""}`}
              onClick={() => setActiveTab("trace")}
            >
              <span className="codicon codicon-pulse" /> 轨迹
            </button>
            <button
              className={`agentrun-tab ${activeTab === "blast" ? "active" : ""}`}
              onClick={() => setActiveTab("blast")}
            >
              <span className="codicon codicon-radio-tower" /> 影响面
            </button>
          </div>
        )}

        <div className="agentrun-summary">
          {run ? (
            <>
              <span className="agentrun-summary-strong">{run.stepCount} 步</span>
              <span className="agentrun-summary-dot">·</span>
              <span>{run.toolCalls} 次工具调用</span>
              <span className="agentrun-summary-dot">·</span>
              <span>{run.iterations} 次迭代</span>
              <span className="agentrun-summary-dot">·</span>
              <span className={`agentrun-mode-badge ${run.mode === "code" ? "code" : "ask"}`}>
                {modeLabel(run.mode)}
              </span>
              <span className="agentrun-summary-q" title={run.question}>
                {run.question}
              </span>
            </>
          ) : (
            <span className="agentrun-summary-dim">选择一条执行记录查看轨迹</span>
          )}
        </div>

        {activeTab === "blast" && selectedRunId != null ? (
          <div style={{ flex: 1, minHeight: 0 }}>
            <BlastRadiusView
              repoId={repoId}
              runId={selectedRunId}
              sessionId={trace?.run?.sessionId}
              openFile={openFile}
            />
          </div>
        ) : (
          <>
            {traceError ? (
              <div className="agentrun-center agentrun-error">加载轨迹失败：{traceError}</div>
            ) : traceLoading ? (
              <div className="agentrun-center">正在加载执行轨迹…</div>
            ) : steps.length === 0 ? (
              <div className="agentrun-center agentrun-empty-sub">该执行没有记录步骤</div>
            ) : (
              <PanelGroup direction="horizontal" autoSaveId="repolens-agentrun-split" className="agentrun-split">
                {/* 时间线 */}
                <Panel minSize={30} defaultSize={52}>
                  <div className="agentrun-timeline">
                    {steps.map((s) => {
                      const meta = STEP_META[s.type];
                      const isOpen = expanded.has(s.stepIndex);
                      const hasFiles = (s.targetFiles?.length ?? 0) > 0;
                      return (
                        <div
                          key={s.id}
                          ref={(el) => {
                            if (el) stepRefs.current.set(s.stepIndex, el);
                            else stepRefs.current.delete(s.stepIndex);
                          }}
                          className={`agentrun-step ${s.type === "WRITE" ? "write" : ""} ${
                            s.stepIndex === selectedStep ? "selected" : ""
                          }`}
                          onClick={() => {
                            setSelectedStep(s.stepIndex);
                            if (hasFiles) openFile(s.targetFiles![0]);
                          }}
                        >
                          <div className="agentrun-step-head">
                            <span
                              className={`codicon ${meta.icon} agentrun-step-icon`}
                              style={{ color: meta.color }}
                            />
                            <span className="agentrun-step-idx">#{s.stepIndex}</span>
                            <span className="agentrun-step-type" style={{ color: meta.color }}>
                              {meta.label}
                            </span>
                            {s.toolName && (
                              <span className="agentrun-step-tool">{s.toolName}</span>
                            )}
                            <span className="agentrun-step-status">{s.status}</span>
                            <button
                              className="agentrun-step-toggle"
                              onClick={(e) => {
                                e.stopPropagation();
                                toggleExpand(s.stepIndex);
                              }}
                              title={isOpen ? "收起详情" : "展开详情"}
                            >
                              <span
                                className={`codicon ${isOpen ? "codicon-chevron-up" : "codicon-chevron-down"}`}
                              />
                            </button>
                          </div>
                          <div className="agentrun-step-summary">{stepSummary(s)}</div>
                          {hasFiles && (
                            <div className="agentrun-step-files">
                              <span className="codicon codicon-file" />
                              {s.targetFiles!.join(", ")}
                            </div>
                          )}
                          {isOpen && (
                            <div className="agentrun-step-detail">
                              {s.thought && (
                                <DetailRow label="思考" text={s.thought} />
                              )}
                              {s.toolArgs && (
                                <DetailRow label="参数" text={s.toolArgs} mono />
                              )}
                              {s.observationSummary && (
                                <DetailRow label="观察" text={s.observationSummary} />
                              )}
                            </div>
                          )}
                        </div>
                      );
                    })}
                  </div>
                </Panel>

                <PanelResizeHandle className="resize-handle-v" />

                {/* 因果 DAG */}
                <Panel minSize={25} defaultSize={48}>
                  <div className="agentrun-dag">
                    <ReactFlow
                      nodes={dag.nodes}
                      edges={dag.edges}
                      onNodeClick={onDagNodeClick}
                      fitView
                      minZoom={0.2}
                      nodesDraggable={false}
                      nodesConnectable={false}
                    >
                      <Background color="#30363d" gap={16} />
                      <Controls showInteractive={false} />
                    </ReactFlow>
                  </div>
                </Panel>
              </PanelGroup>
            )}
          </>
        )}
      </div>
    </div>
  );
}

function DetailRow({ label, text, mono }: { label: string; text: string; mono?: boolean }) {
  return (
    <div className="agentrun-detail-row">
      <span className="agentrun-detail-label">{label}</span>
      <span className={`agentrun-detail-text ${mono ? "mono" : ""}`}>{text}</span>
    </div>
  );
}
