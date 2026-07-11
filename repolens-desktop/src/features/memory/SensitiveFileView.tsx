import { useCallback, useEffect, useState } from "react";
import {
  listSensitiveFiles,
  recomputeSensitiveFiles,
  getSignalNorm,
  severityColor,
} from "../../api/governanceApi";
import type { SensitiveFile } from "../../api/governanceApi";
import { useWorkbench } from "../../state/workbenchStore";
import { AgentsMdProposalModal } from "./AgentsMdProposalModal";
import "./memory.css";

// ─── helpers ──────────────────────────────────────────────────────────────────

function baseName(filePath: string): string {
  const parts = filePath.split(/[/\\]/);
  return parts[parts.length - 1] ?? filePath;
}

// ─── SignalBar ─────────────────────────────────────────────────────────────────

interface SignalBarProps {
  label: string;
  norm: number;
  hint?: string;
}

function SignalBar({ label, norm, hint }: SignalBarProps) {
  const pct = Math.round(norm * 100);
  const barColor = pct >= 70 ? "#e74c3c" : pct >= 40 ? "#f39c12" : "#27ae60";
  return (
    <div className="sf-signal-row" title={hint}>
      <span className="sf-signal-label">
        {label}
        {hint && <span className="sf-signal-help" title={hint}>ⓘ</span>}
      </span>
      <div className="sf-signal-bar-bg">
        <div className="sf-signal-bar" style={{ width: `${pct}%`, background: barColor }} />
      </div>
      <span className="sf-signal-value">{pct}%</span>
    </div>
  );
}

// ─── SensitiveFileDetail ───────────────────────────────────────────────────────

interface DetailProps {
  file: SensitiveFile;
}

function SensitiveFileDetail({ file }: DetailProps) {
  const openFile = useWorkbench((s) => s.openFile);
  const { bg, text } = severityColor(file.severity);

  return (
    <div className="sf-detail">
      {/* 文件路径（可点击打开编辑器） */}
      <div className="sf-detail-filepath">
        <span className="codicon codicon-file" />
        <span
          className="sf-detail-path-link"
          title={`打开 ${file.filePath}`}
          onClick={() => openFile(file.filePath)}
        >
          {file.filePath}
        </span>
        <span
          className="sf-severity-badge"
          style={{ background: bg, color: text }}
        >
          {file.severity}
        </span>
        <span className="sf-score-chip">
          综合分 {file.finalScore.toFixed(1)}
        </span>
      </div>

      {/* 4 信号条 */}
      <div className="sf-signals-section">
        <div className="sf-signals-title">信号明细</div>
        <div className="sf-signals-intro">
          综合分越高，说明这个文件<b>越需要谨慎复审</b>——下面 4 个信号是评分依据（越满越危险）。
        </div>
        <div className="sf-signal-list">
          <SignalBar label="扇入（Fan-in）" norm={getSignalNorm(file, "fanIn")}
            hint="有多少其他文件依赖它。扇入越高，改它越容易牵一发而动全身、波及面越大。" />
          <SignalBar label="变更频率（Churn）" norm={getSignalNorm(file, "churn")}
            hint="最近被改动的频繁程度。改得越勤，越容易累积隐患、越需要盯紧。" />
          <SignalBar label="AI 占比（aiRatio）" norm={getSignalNorm(file, "aiRatio")}
            hint="文件中由 AI 生成/修改的比例。占比越高，越建议人工复核确认。" />
          <SignalBar label="约束命中" norm={getSignalNorm(file, "constraintHit")}
            hint="是否触及安全/鉴权/支付等敏感规则（AGENTS.md 约束）。命中即需重点关注。" />
        </div>
      </div>

      {/* 原因 */}
      {file.reason && (
        <div className="sf-reason-section">
          <div className="sf-signals-title">评分原因</div>
          <div className="sf-reason-text">{file.reason}</div>
        </div>
      )}
    </div>
  );
}

// ─── SensitiveFileView ─────────────────────────────────────────────────────────

/**
 * 敏感文件面板（Feature I Task 6）。
 *
 * 布局：左列文件列表 | 右列信号详情。
 * 工具栏：重新计算 + 查看 AGENTS.md 增补提案。
 */
export function SensitiveFileView() {
  const repoId = useWorkbench((s) => s.repoId);

  const [files, setFiles] = useState<SensitiveFile[]>([]);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [loading, setLoading] = useState(false);
  const [recomputing, setRecomputing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [modalOpen, setModalOpen] = useState(false);

  const selected = files.find((f) => f.id === selectedId) ?? null;

  const load = useCallback(async () => {
    if (!repoId) return;
    setLoading(true);
    setError(null);
    try {
      const data = await listSensitiveFiles(repoId);
      setFiles(data);
      if (data.length > 0 && !selectedId) {
        setSelectedId(data[0].id);
      }
    } catch (err: unknown) {
      setError(String(err));
    } finally {
      setLoading(false);
    }
  }, [repoId]); // eslint-disable-line react-hooks/exhaustive-deps

  const handleRecompute = useCallback(async () => {
    if (!repoId || recomputing) return;
    setRecomputing(true);
    setError(null);
    try {
      const data = await recomputeSensitiveFiles(repoId);
      setFiles(data);
      if (data.length > 0) {
        setSelectedId(data[0].id);
      }
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
      <div className="sf-empty">
        <span className="codicon codicon-shield" />
        <span>请先打开仓库</span>
      </div>
    );
  }

  if (loading && files.length === 0) {
    return <div className="sf-loading">加载敏感文件数据…</div>;
  }

  if (error) {
    return (
      <div className="sf-empty">
        <span className="codicon codicon-error" />
        <span>{error}</span>
        <button onClick={load} style={{ marginTop: 8, padding: "4px 12px", cursor: "pointer" }}>
          重试
        </button>
      </div>
    );
  }

  return (
    <div className="sf-view">
      {/* 左：文件列表 */}
      <div className="sf-sidebar">
        <div className="sf-sidebar-header">
          <span className="sf-sidebar-title">敏感文件 ({files.length})</span>
        </div>
        <div className="sf-list">
          {files.length === 0 && (
            <div className="sf-list-empty">暂无敏感文件（未索引或分数未达阈值）</div>
          )}
          {files.map((f) => {
            const { bg, text } = severityColor(f.severity);
            return (
              <div
                key={f.id}
                className={`sf-item${selectedId === f.id ? " sf-item--active" : ""}`}
                onClick={() => setSelectedId(f.id)}
              >
                <div className="sf-item-header">
                  <span className="sf-item-name" title={f.filePath}>
                    {baseName(f.filePath)}
                  </span>
                  <span className="sf-severity-badge" style={{ background: bg, color: text }}>
                    {f.severity}
                  </span>
                </div>
                <div className="sf-item-meta">
                  <span className="sf-item-path">{f.filePath}</span>
                  <span className="sf-item-score">{f.finalScore.toFixed(1)}</span>
                </div>
              </div>
            );
          })}
        </div>
      </div>

      {/* 右：详情 + 工具栏 */}
      <div className="sf-main">
        <div className="sf-toolbar">
          <span className="sf-toolbar-title">敏感文件分析</span>
          <button
            className="adr-btn"
            onClick={handleRecompute}
            disabled={recomputing || loading}
            title="重新计算仓库所有敏感文件评分"
          >
            {recomputing ? "计算中…" : "重新计算"}
          </button>
          <button
            className="adr-btn adr-btn--secondary"
            onClick={() => setModalOpen(true)}
            title="查看 AGENTS.md 新约定增补提案"
          >
            查看 AGENTS.md 增补提案
          </button>
        </div>

        {error && <div className="adr-error-banner">{error}</div>}

        <div className="sf-content">
          {selected ? (
            <SensitiveFileDetail file={selected} />
          ) : (
            <div className="sf-empty" style={{ height: "auto", paddingTop: 40 }}>
              <span className="codicon codicon-shield" />
              <span>
                {files.length === 0
                  ? "暂无敏感文件，点击「重新计算」触发扫描"
                  : "选择左侧文件查看详情"}
              </span>
            </div>
          )}
        </div>
      </div>

      {/* AGENTS.md 提案 Modal */}
      {modalOpen && <AgentsMdProposalModal onClose={() => setModalOpen(false)} />}
    </div>
  );
}
