import { useEffect, useState } from "react";
import { DebtUnit, FileExplanation, RepayPath, BAND_COLORS, SIGNAL_LABELS } from "../../types/debt";
import { fetchRepayPath, explainFile } from "../../api/debtApi";
import { useWorkbench } from "../../state/workbenchStore";
import { renderMarkdown } from "../chat/ccMarkdown";

interface Props {
  unit: DebtUnit;
  onDebtUpdated?: () => void; // reserved: callback when debt score changes
}

function SignalBar({ label, norm, degraded }: { label: string; norm: number; degraded?: boolean }) {
  const pct = Math.round(norm * 100);
  const barColor = pct >= 70 ? "#e74c3c" : pct >= 40 ? "#f39c12" : "#27ae60";

  return (
    <div className="debt-signal-row">
      <span className="debt-signal-label">{label}</span>
      <div className="debt-signal-bar-bg">
        {degraded ? null : (
          <div
            className="debt-signal-bar"
            style={{ width: `${pct}%`, background: barColor }}
          />
        )}
      </div>
      <span className="debt-signal-value">
        {degraded ? (
          <span className="debt-signal-degraded">无数据</span>
        ) : (
          `${pct}%`
        )}
      </span>
    </div>
  );
}

// ------------------------------------------------------------------ //
//  Explain panel（帮助理解：AI 讲解 + 相关文件/符号可点击跳转）           //
// ------------------------------------------------------------------ //

type ExplainState =
  | { phase: "idle" }
  | { phase: "loading" }
  | { phase: "ready"; data: FileExplanation }
  | { phase: "error"; message: string };

interface ExplainPanelProps {
  repoId: number;
  fileId: number;
}

function ExplainPanel({ repoId, fileId }: ExplainPanelProps) {
  const [state, setState] = useState<ExplainState>({ phase: "idle" });
  const openFile = useWorkbench((s) => s.openFile);

  const handleExplain = async () => {
    setState({ phase: "loading" });
    try {
      const data = await explainFile(repoId, fileId);
      setState({ phase: "ready", data });
    } catch (err) {
      setState({ phase: "error", message: "生成讲解失败，请稍后再试。" });
    }
  };

  const handleRetry = () => setState({ phase: "idle" });

  if (state.phase === "idle") {
    return (
      <button
        onClick={handleExplain}
        style={{
          padding: "6px 14px",
          background: "#1f6feb",
          color: "#fff",
          border: "none",
          borderRadius: 4,
          cursor: "pointer",
          fontSize: 12,
        }}
      >
        让 AI 讲解这个文件
      </button>
    );
  }

  if (state.phase === "loading") {
    return <div style={{ fontSize: 12, color: "#8b949e" }}>AI 讲解生成中…</div>;
  }

  if (state.phase === "error") {
    return (
      <div>
        <div style={{ fontSize: 12, color: "#e74c3c", marginBottom: 6 }}>{state.message}</div>
        <button onClick={handleRetry} style={{ fontSize: 12, padding: "4px 10px", cursor: "pointer", background: "transparent", border: "1px solid #30363d", color: "#e6edf3", borderRadius: 4 }}>
          重试
        </button>
      </div>
    );
  }

  // phase === "ready"
  const { data } = state;
  return (
    <div className="debt-explain">
      {data.degraded && (
        <div style={{ fontSize: 11, color: "#f39c12", marginBottom: 8 }}>
          AI 服务暂不可用，以下为降级说明。
        </div>
      )}

      {/* 讲解正文（markdown） */}
      <div className="debt-explain-body cc-md" style={{ fontSize: 13, lineHeight: 1.7, color: "#c9d1d9" }}>
        {renderMarkdown(data.explanation)}
      </div>

      {/* 相关文件 */}
      {data.relatedFiles.length > 0 && (
        <>
          <div style={{ fontSize: 11, color: "#8b949e", margin: "12px 0 6px", textTransform: "uppercase" }}>
            相关文件（点击打开）
          </div>
          {data.relatedFiles.map((rf, i) => (
            <div
              key={i}
              className="debt-related-item"
              onClick={() => openFile(rf.path)}
              title={`打开 ${rf.path}`}
              style={{ cursor: "pointer" }}
            >
              <span className="debt-related-path">{rf.path}</span>
              {rf.reason && <span className="debt-related-reason"> — {rf.reason}</span>}
            </div>
          ))}
        </>
      )}

      {/* 相关符号 */}
      {data.relatedSymbols.length > 0 && (
        <>
          <div style={{ fontSize: 11, color: "#8b949e", margin: "12px 0 6px", textTransform: "uppercase" }}>
            相关类/方法/函数
          </div>
          {data.relatedSymbols.map((rs, i) => {
            const clickable = !!rs.path;
            return (
              <div
                key={i}
                className="debt-related-item"
                onClick={clickable ? () => openFile(rs.path!) : undefined}
                title={clickable ? `打开 ${rs.path}` : undefined}
                style={{ cursor: clickable ? "pointer" : "default" }}
              >
                <span className="debt-related-symbol">{rs.name}</span>
                {rs.path && <span className="debt-related-path"> · {rs.path}</span>}
                {rs.reason && <span className="debt-related-reason"> — {rs.reason}</span>}
              </div>
            );
          })}
        </>
      )}

      <button
        onClick={handleExplain}
        style={{ marginTop: 12, fontSize: 12, padding: "4px 10px", cursor: "pointer", background: "transparent", border: "1px solid #30363d", color: "#e6edf3", borderRadius: 4 }}
      >
        重新生成讲解
      </button>
    </div>
  );
}

// ------------------------------------------------------------------ //
//  Main component                                                       //
// ------------------------------------------------------------------ //

/**
 * 下钻面板：信号雷达（七维条形）+ 偿债路径（理由卡片 + 记忆 + AI 文件讲解）。
 */
export function DebtDrilldown({ unit }: Props) {
  const repoId = useWorkbench((s) => s.repoId);
  const openFile = useWorkbench((s) => s.openFile);
  const [repay, setRepay] = useState<RepayPath | null>(null);
  const [repayLoading, setRepayLoading] = useState(false);
  const [repayOpen, setRepayOpen] = useState(false);

  useEffect(() => {
    setRepay(null);
    setRepayOpen(false);
  }, [unit.fileId]);

  const handleRepay = async () => {
    if (!repoId) return;
    setRepayOpen(true);
    if (repay) return;
    setRepayLoading(true);
    try {
      const data = await fetchRepayPath(repoId, unit.fileId);
      setRepay(data);
    } catch (err) {
      console.warn("fetchRepayPath failed", err);
    } finally {
      setRepayLoading(false);
    }
  };

  const { signals } = unit;
  const bandColor = BAND_COLORS[unit.band] ?? "#8b949e";

  const reviewLevelText = [
    "从未复核（level 0）",
    "仅点接受（level 1）",
    "查看 diff（level 2）",
    "通过测验（level 3）",
  ][signals.s2ReviewLevel] ?? "未知";

  return (
    <div className="debt-drilldown">
      {/* 标题 */}
      <div className="debt-drilldown-title">
        <span
          style={{
            display: "inline-block",
            width: 10,
            height: 10,
            borderRadius: "50%",
            background: bandColor,
            flexShrink: 0,
          }}
        />
        <span
          className="debt-drilldown-path"
          style={{ flex: 1, cursor: "pointer" }}
          title={`打开 ${unit.filePath}`}
          onClick={() => openFile(unit.filePath)}
        >
          {unit.filePath}
        </span>
        <span className={`debt-score-badge ${unit.band.toLowerCase()}`}>
          {unit.band} · {unit.score}
        </span>
      </div>

      {/* amp 解释 */}
      {signals.ampFactor > 1 && (
        <div
          style={{
            background: "rgba(231,76,60,0.1)",
            border: "1px solid rgba(231,76,60,0.3)",
            borderRadius: 4,
            padding: "6px 10px",
            fontSize: 12,
            color: "#e74c3c",
            marginBottom: 12,
          }}
        >
          ⚠ 高危放大 ×{signals.ampFactor.toFixed(1)}：AI 写（S1≥0.5）+ 未读懂（S2 level≤1）+ 复杂（S4≥0.6）同时触发
        </div>
      )}

      {/* 信号雷达 */}
      <div className="debt-drilldown-section">
        <h4>七信号明细</h4>
        <div className="debt-radar">
          <SignalBar label={SIGNAL_LABELS.S1} norm={signals.s1Norm} />
          <SignalBar label={SIGNAL_LABELS.S2} norm={signals.s2Norm} />
          <SignalBar label={SIGNAL_LABELS.S3} norm={signals.s3Norm} />
          <SignalBar label={SIGNAL_LABELS.S4} norm={signals.s4Norm} />
          <SignalBar label={SIGNAL_LABELS.S5} norm={signals.s5Norm} />
          <SignalBar label={SIGNAL_LABELS.S6} norm={signals.s6Norm} degraded={signals.s6Degraded} />
          <SignalBar label={SIGNAL_LABELS.S7} norm={signals.s7Norm} />
        </div>
        <div style={{ marginTop: 8, fontSize: 11, color: "#8b949e" }}>
          base={signals.base.toFixed(3)} · amp×{signals.ampFactor} · score={signals.score}
        </div>
      </div>

      {/* 信号文字说明 */}
      <div className="debt-drilldown-section">
        <h4>信号说明</h4>
        <div style={{ fontSize: 12, color: "#8b949e", lineHeight: 1.6 }}>
          <div>AI 改动行：{signals.s1AiChangedLines} / {signals.s1LineCount} 行</div>
          <div>复核等级：{reviewLevelText}</div>
          <div>理由记录：{signals.s3HasRationale ? "有" : "无"}</div>
          <div>最大认知复杂度：{signals.s4MaxCognitive}（圈复杂度：{signals.s4MaxCyclomatic}）</div>
          <div>近 14d 改动次数：{signals.s5Churn14dCount}</div>
          <div>S6（人类改动间隔）：{signals.s6Degraded ? "数据不足（MVP 降级）" : signals.s6Norm.toFixed(2)}</div>
          <div>测试文件：{signals.s7HasTestFile ? "有" : "无"}</div>
        </div>
      </div>

      {/* 偿债路径 */}
      <div className="debt-drilldown-section">
        <h4>偿债路径</h4>
        {!repayOpen ? (
          <button
            onClick={handleRepay}
            style={{
              padding: "6px 14px",
              background: "#238636",
              color: "#fff",
              border: "none",
              borderRadius: 4,
              cursor: "pointer",
              fontSize: 12,
            }}
          >
            查看偿债路径
          </button>
        ) : repayLoading ? (
          <div style={{ fontSize: 12, color: "#8b949e" }}>加载偿债路径…</div>
        ) : repay ? (
          <div className="debt-repay-panel">
            {/* 理由卡片 */}
            {repay.rationales.length > 0 && (
              <>
                <div style={{ fontSize: 11, color: "#8b949e", marginBottom: 6, textTransform: "uppercase" }}>
                  现成理由卡片
                </div>
                {repay.rationales.map((r, i) => (
                  <div key={i} className="debt-repay-item">
                    {r}
                  </div>
                ))}
              </>
            )}

            {/* 长期记忆 */}
            {repay.memories.length > 0 && (
              <>
                <div style={{ fontSize: 11, color: "#8b949e", margin: "10px 0 6px", textTransform: "uppercase" }}>
                  相关长期记忆
                </div>
                {repay.memories.map((m, i) => (
                  <div key={i} className="debt-repay-item">
                    {m}
                  </div>
                ))}
              </>
            )}

            {/* AI 文件讲解（帮助理解，替代考试式出题） */}
            {repoId && (
              <div style={{ marginTop: 12 }}>
                <div style={{ fontSize: 11, color: "#8b949e", marginBottom: 8, textTransform: "uppercase" }}>
                  文件讲解（让 AI 帮你读懂这个文件）
                </div>
                <ExplainPanel repoId={repoId} fileId={unit.fileId} />
              </div>
            )}
          </div>
        ) : (
          <div style={{ fontSize: 12, color: "#8b949e" }}>加载失败，请重试</div>
        )}
      </div>
    </div>
  );
}
