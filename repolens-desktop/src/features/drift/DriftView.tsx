import { useCallback, useEffect, useMemo, useState } from "react";
import { useWorkbench } from "../../state/workbenchStore";
import {
  captureSnapshot,
  fetchEvolution,
  DriftItem,
  DriftReport,
  EvolutionTimeline,
  SnapshotView,
} from "../../api/driftApi";
import "./drift.css";

/**
 * M9 架构漂移观测视图。
 *
 * 调用图本身没有历史，所以内核沿时间抓「调用图快照」、用整图哈希当结构指纹，
 * 相邻快照两两比对得出结构漂移。本视图读 /drift/evolution 为主，把快照排成
 * 时间轴、把每段 transition 标成「稳定 / N 处漂移」，选中一段展开明细。
 *
 * 诚实性（简历卖点，务必在 UI 体现）：
 * - changed=false 不是「没数据」而是「这段时间架构稳定」的有用信号，正面展示；
 * - 漂移比的是语义稳定 key，重索引导致的内部 id 变化不算漂移。
 */

// 从 driftType（形如 NODE_ADDED / EDGE_REMOVED / FILE_CHANGED）拆出后缀，决定语义与配色。
type DriftKind = "ADDED" | "REMOVED" | "CHANGED" | "OTHER";

function kindOf(driftType: string): DriftKind {
  if (driftType.endsWith("_ADDED")) return "ADDED";
  if (driftType.endsWith("_REMOVED")) return "REMOVED";
  if (driftType.endsWith("_CHANGED")) return "CHANGED";
  return "OTHER";
}

const KIND_META: Record<DriftKind, { label: string; className: string }> = {
  ADDED: { label: "新增", className: "drift-kind-added" },
  REMOVED: { label: "移除", className: "drift-kind-removed" },
  CHANGED: { label: "变更", className: "drift-kind-changed" },
  OTHER: { label: "其他", className: "drift-kind-other" },
};

/** 短哈希：图哈希/commit 都很长，界面只取前 8 位便于扫读。 */
function shortHash(h: string | null | undefined): string {
  if (!h) return "—";
  return h.length > 8 ? h.slice(0, 8) : h;
}

export function DriftView() {
  const repoId = useWorkbench((s) => s.repoId);
  const openFile = useWorkbench((s) => s.openFile);

  const [evolution, setEvolution] = useState<EvolutionTimeline | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [capturing, setCapturing] = useState(false);
  // 选中的 transition 序号（在 transitions 数组里的下标）；null = 尚未手动选择，用默认。
  const [selectedIdx, setSelectedIdx] = useState<number | null>(null);

  const load = useCallback(() => {
    if (repoId == null) return;
    setLoading(true);
    setError(null);
    fetchEvolution(repoId)
      .then((t) => setEvolution(t))
      .catch((e: unknown) => setError(String(e)))
      .finally(() => setLoading(false));
  }, [repoId]);

  useEffect(() => {
    setEvolution(null);
    setSelectedIdx(null);
    load();
  }, [repoId, load]);

  const handleCapture = useCallback(() => {
    if (repoId == null || capturing) return;
    setCapturing(true);
    setError(null);
    captureSnapshot(repoId)
      .then(() => {
        // 抓完总有一段新 transition 可看，默认把选中重置到「最后一段」。
        setSelectedIdx(null);
        load();
      })
      .catch((e: unknown) => setError(String(e)))
      .finally(() => setCapturing(false));
  }, [repoId, capturing, load]);

  const snapshots: SnapshotView[] = evolution?.snapshots ?? [];
  const transitions: DriftReport[] = evolution?.transitions ?? [];

  // 默认选中：最后一段「发生了漂移」的 transition；若全都稳定，则选最后一段。
  const effectiveIdx = useMemo(() => {
    if (transitions.length === 0) return -1;
    if (selectedIdx != null && selectedIdx >= 0 && selectedIdx < transitions.length) {
      return selectedIdx;
    }
    for (let i = transitions.length - 1; i >= 0; i--) {
      if (transitions[i].changed) return i;
    }
    return transitions.length - 1;
  }, [transitions, selectedIdx]);

  const selected: DriftReport | null =
    effectiveIdx >= 0 ? transitions[effectiveIdx] : null;

  // 把选中报告的 drifts 按 kind 分组，方便着色渲染。
  const grouped = useMemo(() => {
    const g: Record<DriftKind, DriftItem[]> = { ADDED: [], REMOVED: [], CHANGED: [], OTHER: [] };
    if (selected) for (const d of selected.drifts) g[kindOf(d.driftType)].push(d);
    return g;
  }, [selected]);

  // ── 顶部区（标题 + 抓取按钮 + 说明），各状态复用 ────────────────────────
  const header = (
    <div className="drift-header">
      <span className="drift-title">📡 架构漂移</span>
      <button
        className="drift-btn drift-btn-capture"
        onClick={handleCapture}
        disabled={repoId == null || capturing}
        title="抓一次当前调用图快照并落库，与上一快照比对"
      >
        {capturing ? (
          <>
            <span className="codicon codicon-loading codicon-modifier-spin" />
            &nbsp;抓取中…
          </>
        ) : (
          "抓取当前快照"
        )}
      </button>
      <span className="drift-subtitle">
        调用图无历史——内核沿时间抓快照、图哈希当指纹，比对结构漂移
      </span>
    </div>
  );

  // ── 各态渲染 ──────────────────────────────────────────────────────────
  if (repoId == null) {
    return <div className="drift-view drift-state-center">请先打开一个仓库</div>;
  }

  if (loading && evolution == null) {
    return (
      <div className="drift-view">
        {header}
        <div className="drift-state-center" style={{ flex: 1 }}>
          <span className="codicon codicon-loading codicon-modifier-spin" style={{ marginRight: 8 }} />
          加载演化时间线中…
        </div>
      </div>
    );
  }

  if (error && evolution == null) {
    return (
      <div className="drift-view">
        {header}
        <div className="drift-state-center drift-error" style={{ flex: 1 }}>
          加载失败：{error}
        </div>
      </div>
    );
  }

  // 无快照：引导用户抓第一张。
  if (snapshots.length === 0) {
    return (
      <div className="drift-view">
        {header}
        <div className="drift-state-center" style={{ flex: 1 }}>
          <span className="codicon codicon-radio-tower" style={{ fontSize: 36, marginBottom: 12 }} />
          <div>还没有任何快照</div>
          <div className="drift-hint-text">
            点上方「抓取当前快照」给当前调用图拍一张结构指纹。之后每抓一张，就能和上一张比对出架构漂移。
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="drift-view">
      {header}
      {error && <div className="drift-inline-error">操作失败：{error}</div>}

      <div className="drift-body">
        {/* ── 快照时间轴：快照 + 夹在中间的 transition 标记 ──────────────── */}
        <div className="drift-timeline">
          {snapshots.map((s, i) => {
            // 第 i 段 transition 连接快照 i 与 i+1；渲染在快照 i 之后。
            const t = i > 0 ? transitions[i - 1] : null;
            return (
              <div key={s.snapshotId} className="drift-timeline-row">
                {/* 段：从上一快照到当前快照的 transition */}
                {t && (
                  <div
                    className={
                      "drift-transition" +
                      (i - 1 === effectiveIdx ? " selected" : "") +
                      (t.changed ? " changed" : " stable")
                    }
                    onClick={() => setSelectedIdx(i - 1)}
                    title={t.changed ? `${t.drifts.length} 处漂移，点击查看明细` : "结构指纹一致，架构稳定"}
                  >
                    {t.changed ? (
                      <span className="drift-transition-label">▾ {t.drifts.length} 处漂移</span>
                    ) : (
                      <span className="drift-transition-label drift-stable-label">✓ 架构稳定</span>
                    )}
                  </div>
                )}

                {/* 节点：一个快照 */}
                <div className="drift-snapshot">
                  <div className="drift-snapshot-head">
                    <span className="drift-seq">#{s.seq}</span>
                    {s.label && <span className="drift-snapshot-label">{s.label}</span>}
                    {s.commitRef && (
                      <span className="drift-snapshot-commit" title={s.commitRef}>
                        @{shortHash(s.commitRef)}
                      </span>
                    )}
                    <span className="drift-snapshot-hash" title={s.graphHash}>
                      {shortHash(s.graphHash)}
                    </span>
                  </div>
                  <div className="drift-snapshot-stats">
                    {s.nodeCount} 节点 · {s.edgeCount} 边 · {s.fileCount} 文件
                  </div>
                </div>
              </div>
            );
          })}

          {/* 只有一个快照：还无从比对，提示再抓一次。 */}
          {snapshots.length === 1 && (
            <div className="drift-hint-inline">再抓一次快照，即可比对两张之间的架构演化。</div>
          )}
        </div>

        {/* ── 选中 transition 的漂移明细 ─────────────────────────────────── */}
        <div className="drift-detail">
          {selected == null ? (
            <div className="drift-state-center drift-detail-empty">
              选中一段查看漂移明细
            </div>
          ) : !selected.changed ? (
            // changed=false 正面展示：不是空白，而是「这段稳定」的有用信号。
            <div className="drift-state-center drift-detail-stable">
              <span className="codicon codicon-pass" style={{ fontSize: 34, marginBottom: 10 }} />
              <div className="drift-stable-title">架构稳定 ✓</div>
              <div className="drift-hint-text">
                快照 {shortHash(selected.fromHash)} → {shortHash(selected.toHash)} 结构指纹一致：
                这段时间没有节点/边/文件级的结构漂移。（比对用语义稳定 key，重索引造成的内部 id 变化不算漂移。）
              </div>
            </div>
          ) : (
            <>
              <div className="drift-detail-head">
                <span className="drift-detail-title">
                  {shortHash(selected.fromHash)} → {shortHash(selected.toHash)}
                </span>
                <span className="drift-detail-count">{selected.drifts.length} 处漂移</span>
              </div>

              {/* 图例：与下方分组着色对应 */}
              <div className="drift-legend">
                {(["ADDED", "REMOVED", "CHANGED"] as DriftKind[]).map((k) =>
                  grouped[k].length > 0 ? (
                    <span key={k} className={`drift-legend-item ${KIND_META[k].className}`}>
                      ● {KIND_META[k].label} {grouped[k].length}
                    </span>
                  ) : null,
                )}
              </div>

              <div className="drift-item-list">
                {(["ADDED", "REMOVED", "CHANGED", "OTHER"] as DriftKind[]).map((k) =>
                  grouped[k].map((d) => (
                    <div key={`${d.driftType}-${d.entityKeyHash}`} className="drift-item">
                      <span className={`drift-item-badge ${KIND_META[k].className}`}>
                        {d.driftType}
                      </span>
                      <div className="drift-item-main">
                        <div className="drift-item-desc">{d.entityDesc}</div>
                        <div className="drift-item-meta">
                          {d.filePath && (
                            <span
                              className="drift-item-file"
                              onClick={() => openFile(d.filePath as string)}
                              title="打开文件"
                            >
                              {d.filePath}
                            </span>
                          )}
                          {d.language && <span className="drift-item-lang">{d.language}</span>}
                          {d.attributedCommit && (
                            <span className="drift-item-attr" title="归因 commit">
                              commit {shortHash(d.attributedCommit)}
                            </span>
                          )}
                          {d.attributedSessionId != null && (
                            <span className="drift-item-attr" title="归因会话">
                              会话 #{d.attributedSessionId}
                            </span>
                          )}
                        </div>
                      </div>
                    </div>
                  )),
                )}
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
