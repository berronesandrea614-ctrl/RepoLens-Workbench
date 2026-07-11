import { useCallback, useEffect, useRef, useState } from "react";
import "./insight.css";
import { RequirementInsightVO, FlowNode } from "./insightTypes";
import { fetchRequirementInsight } from "./insightApi";
import { isDegradeMode, isNoplanMode, shouldShowDeviation } from "./insightUtils";
import { IntentBar } from "./IntentBar";
import { DeviationBanner } from "./DeviationBanner";
import { PlanStep } from "./PlanStep";
import { DataflowPanorama } from "./DataflowPanorama";
import { InsightFooter } from "./InsightFooter";
import { DiffModal } from "../../chat/DiffModal";
import { fetchAllChanges } from "../../../api/changeApi";
import { FileChangeDetail } from "../../../types/change";
import { ReconciliationLane } from "./ReconciliationLane";

interface RequirementInsightCardProps {
  repoId: number;
  reqId: number;
  /** Displayed in breadcrumbs by the parent component; unused inside the card itself. */
  reqTitle?: string;
  onViewSubgraph?: () => void;
  openFile: (path: string, line?: number) => void;
}

type TabId = "req" | "pano";

/** 骨架屏占位 */
function SkeletonView() {
  return (
    <div className="ins-skeleton">
      {[80, 60, 95, 50, 70].map((w, i) => (
        <div key={i} className="ins-skel-line" style={{ width: `${w}%` }} />
      ))}
    </div>
  );
}

export function RequirementInsightCard({
  repoId,
  reqId,
  reqTitle: _reqTitle,
  onViewSubgraph,
  openFile,
}: RequirementInsightCardProps) {
  const [tab, setTab] = useState<TabId>("req");
  const [vo, setVo] = useState<RequirementInsightVO | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // ── diff modal state ─────────────────────────────────
  const [diffState, setDiffState] = useState<{
    filePath: string;
    old: string;
    neu: string;
  } | null>(null);
  const [diffLoading, setDiffLoading] = useState(false);
  const [changeDetailsCache, setChangeDetailsCache] = useState<FileChangeDetail[] | null>(null);

  // ── generation guard ─────────────────────────────────
  // Increment on every new (reqId, repoId) pair; stale responses are discarded.
  const genRef = useRef(0);

  // ── selected step ────────────────────────────────────
  const [selStepIdx, setSelStepIdx] = useState<number | null>(null);
  const stepRefs = useRef<(HTMLDivElement | null)[]>([]);

  // ── load insight ─────────────────────────────────────
  const load = useCallback(async () => {
    const myGen = ++genRef.current;
    setLoading(true);
    setError(null);
    setVo(null);
    setSelStepIdx(null);
    setChangeDetailsCache(null);
    try {
      const result = await fetchRequirementInsight(repoId, reqId);
      if (genRef.current !== myGen) return; // stale — discard
      setVo(result);
    } catch (e: unknown) {
      if (genRef.current !== myGen) return;
      const msg = e instanceof Error ? e.message : String(e);
      setError(`加载需求洞察失败：${msg}`);
    } finally {
      if (genRef.current === myGen) setLoading(false);
    }
  }, [repoId, reqId]);

  useEffect(() => {
    void load();
  }, [load]);

  // ── node click: open diff modal ───────────────────────
  async function handleNodeDiff(node: FlowNode) {
    if (!node.changeId) {
      // No changeId — fall back to opening the file
      if (node.filePath) openFile(node.filePath, node.startLine);
      return;
    }
    const filePath = node.filePath ?? "(未知文件)";
    setDiffLoading(true);
    setDiffState({ filePath, old: "", neu: "" });
    try {
      let details = changeDetailsCache;
      if (!details) {
        // Fetch all changes for the repo (sessionId optional on backend)
        details = await fetchAllChanges(repoId);
        setChangeDetailsCache(details);
      }
      const found = details.find((d) => d.id === node.changeId);
      if (found) {
        setDiffState({
          filePath: found.filePath,
          old: found.oldContent ?? "",
          neu: found.newContent ?? "",
        });
      }
    } catch {
      // On error, keep the modal open showing empty diff
    } finally {
      setDiffLoading(false);
    }
  }

  // ── node click: open file in editor ──────────────────
  function handleNodeOpenFile(node: FlowNode, line?: number) {
    if (node.filePath) openFile(node.filePath, line);
  }

  // ── evidence chip click ───────────────────────────────
  function handleEvidenceClick(filePath: string) {
    openFile(filePath);
  }

  // ── jump to first off step ────────────────────────────
  function jumpToOff() {
    if (!vo) return;
    const offIdx = vo.steps.findIndex((s) => s.kind === "off");
    if (offIdx >= 0) {
      setSelStepIdx(offIdx);
      setTab("req");
      const el = stepRefs.current[offIdx];
      if (el) el.scrollIntoView({ behavior: "smooth", block: "center" });
    }
  }

  // ─────────────────────────────────────────────────────
  if (loading) {
    return (
      <div className="ins-root">
        <div className="ins-tabs-bar">
          <button className="ins-tab on">📋 需求视图</button>
          <button className="ins-tab">🌊 数据流全景</button>
        </div>
        <SkeletonView />
      </div>
    );
  }

  if (error) {
    return (
      <div className="ins-root">
        <div className="ins-error">{error}</div>
      </div>
    );
  }

  if (!vo) return null;

  const degrade = isDegradeMode(vo);
  const noplan = isNoplanMode(vo);
  const hasDeviation = shouldShowDeviation(vo.deviation);

  const degradeNote = degrade
    ? "这是一个纯问答需求（未进入 code 模式），没有代码改动。下面只展示 AI 读了哪些文件得出的回答，无改动 / 偏差 / 数据流全景。"
    : noplan
    ? undefined
    : undefined;

  return (
    <div className="ins-root">
      {/* ── Tab bar ── */}
      <div className="ins-tabs-bar">
        <button
          className={`ins-tab${tab === "req" ? " on" : ""}`}
          onClick={() => setTab("req")}
        >
          📋 需求视图
        </button>
        <button
          className={`ins-tab${tab === "pano" ? " on" : ""}`}
          onClick={() => setTab("pano")}
        >
          🌊 数据流全景
        </button>
      </div>

      {/* ── Legend ── */}
      <div className="ins-legend">
        <span><i className="ins-dotc" style={{ background: "#3a4656" }} />入口/请求</span>
        <span><i className="ins-dotc" style={{ background: "#5f5230" }} />~ 修改</span>
        <span><i className="ins-dotc" style={{ background: "#2f5a3f" }} />+ 新增</span>
        <span><i className="ins-dotc" style={{ background: "#803232" }} />敏感/风险</span>
        <span><i className="ins-dotc" style={{ background: "#803262" }} />计划外</span>
        <span><i className="ins-dotc" style={{ background: "#3c4c5e" }} />外部/存储</span>
        <span><i className="ins-dotc" style={{ background: "#3a3a44" }} />未触碰</span>
      </div>

      {/* ── Content area ── */}
      <div className="ins-content">
        {tab === "req" && (
          <>
            <IntentBar
              intent={vo.intent}
              approach={vo.approach}
              chips={degrade ? undefined : vo.chips}
              isDegrade={degrade}
              degradeNote={degradeNote}
            />

            {noplan && (
              <div className="ins-degrade">
                该需求无结构化计划（可能是老数据或计划功能关闭时产生的），仅展示改动概览。
              </div>
            )}

            {!degrade && hasDeviation && (
              <DeviationBanner deviation={vo.deviation} onJumpToOff={jumpToOff} />
            )}

            {/* ── B P1: 对账泳道 (置于偏差 banner 之后) ── */}
            <ReconciliationLane repoId={repoId} requirementId={reqId} />

            <div className="ins-steps">
              {vo.steps.map((step, idx) => (
                <div
                  key={step.index}
                  ref={(el) => { stepRefs.current[idx] = el; }}
                >
                  <PlanStep
                    step={step}
                    selected={selStepIdx === idx}
                    onSelect={() => setSelStepIdx(idx)}
                    onNodeDiff={(n) => void handleNodeDiff(n)}
                    onNodeOpenFile={handleNodeOpenFile}
                    onEvidenceClick={handleEvidenceClick}
                  />
                </div>
              ))}
            </div>

            <InsightFooter
              footer={vo.footer}
              onViewSubgraph={onViewSubgraph}
            />
          </>
        )}

        {tab === "pano" && (
          <DataflowPanorama
            panorama={vo.panorama}
            hasChanges={vo.hasChanges}
            onNodeDiff={(n) => void handleNodeDiff(n)}
            onNodeOpenFile={handleNodeOpenFile}
          />
        )}
      </div>

      {/* ── Diff modal ── */}
      {diffState && (
        <DiffModal
          filePath={diffState.filePath}
          oldContent={diffState.old}
          newContent={diffState.neu}
          loading={diffLoading}
          onClose={() => setDiffState(null)}
        />
      )}
    </div>
  );
}
