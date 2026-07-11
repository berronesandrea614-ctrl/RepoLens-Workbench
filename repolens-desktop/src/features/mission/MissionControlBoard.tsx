import { useCallback, useEffect, useState } from "react";
import { fetchMissionOverview } from "../../api/missionApi";
import type { AgentLane, MissionControl, ReviewItem } from "../../api/missionApi";
import { useWorkbench } from "../../state/workbenchStore";
import {
  laneNeedsHighlight,
  deviationNeedsAlert,
  formatCoverage,
  formatStatus,
  statusColor,
  severityBadgeColor,
  isInterrupt,
} from "./missionHelpers";
import "./mission.css";

// ─── LaneCard ─────────────────────────────────────────────────────────────────

interface LaneCardProps {
  lane: AgentLane;
  expanded: boolean;
  onToggle: () => void;
}

function LaneCard({ lane, expanded, onToggle }: LaneCardProps) {
  const highlight = laneNeedsHighlight(lane);
  const sColor = statusColor(lane.status);
  const sLabel = formatStatus(lane.status);

  const claimedIcon =
    lane.claimedVerified
      ? "✅ 自报已验证"
      : lane.claimedSuccess
      ? "✅ 自报成功"
      : null;

  const devAlert =
    lane.deviation != null && deviationNeedsAlert(lane.deviation.trustFlag);

  let cardClass = "mission-lane-card";
  if (highlight) cardClass += " needs-attention";
  if (expanded) cardClass += " expanded";

  return (
    <div className={cardClass} onClick={onToggle}>
      {/* 头行：引擎 + status + lane ID */}
      <div className="mission-lane-header">
        <span className="mission-engine-badge">{lane.engine}</span>
        <span
          className="mission-status-badge"
          style={{ background: `${sColor}22`, color: sColor }}
        >
          {sLabel}
        </span>
        <span className="mission-lane-id">#{lane.laneId}</span>
      </div>

      {/* claimed */}
      {claimedIcon && (
        <div className="mission-claimed-row">{claimedIcon}</div>
      )}

      {/* degraded 占位 */}
      {lane.degraded ? (
        <div className="mission-lane-degraded">⚠ 数据降级，部分信息不可用</div>
      ) : (
        <>
          {/* planLine：后端可能给占位值「计划未结构化」（该次运行未产出结构化步骤，
              如纯问答/未走 Plan 模式）——此时以弱化样式+释义呈现，不当作真实计划内容。 */}
          {(() => {
            const unstructured = !lane.planLine || lane.planLine === "计划未结构化";
            return (
              <div
                className={`mission-plan-line${unstructured ? " mission-plan-line--placeholder" : ""}`}
                title={unstructured
                  ? "该次运行未产出结构化计划步骤（可能是纯问答，或未启用 Plan 模式）"
                  : lane.planLine}
              >
                {unstructured ? "未产出结构化计划" : lane.planLine}
              </div>
            );
          })()}

          {/* changesLine：去重——changedFileCount 为 0 时不再叠加「改动 0 文件」的冗余描述 */}
          <div className="mission-changes-line">
            {lane.changedFileCount === 0
              ? "本次无文件改动"
              : `改 ${lane.changedFileCount} 文件${lane.changesLine ? ` · ${lane.changesLine}` : ""}`}
          </div>

          {/* 徽章行 */}
          <div className="mission-badges-row">
            {lane.risk.blockCount > 0 && (
              <span className="mission-badge block">
                🔴 {lane.risk.blockCount}
              </span>
            )}
            {lane.risk.warnCount > 0 && (
              <span className="mission-badge warn">
                🟡 {lane.risk.warnCount}
              </span>
            )}
            {lane.debtCount > 0 && (
              <span className="mission-badge debt">
                📊 债务 {lane.debtCount}
              </span>
            )}
            {lane.deviation != null && (
              <span className={`mission-badge ${devAlert ? "deviation" : "deviation-ok"}`}>
                {devAlert
                  ? `⚠ ${lane.deviation.trustFlag} 覆盖 ${formatCoverage(lane.deviation.coverage)}`
                  : `覆盖 ${formatCoverage(lane.deviation.coverage)}`}
              </span>
            )}
          </div>

          {/* 展开详情 */}
          {expanded && (
            <LaneDetail lane={lane} />
          )}
        </>
      )}
    </div>
  );
}

// ─── LaneDetail ───────────────────────────────────────────────────────────────

function LaneDetail({ lane }: { lane: AgentLane }) {
  return (
    <div className="mission-lane-detail" onClick={(e) => e.stopPropagation()}>
      {/* planLine 全文 */}
      <div className="mission-detail-section">
        <div className="mission-detail-label">计划</div>
        <div className="mission-detail-value">{lane.planLine || "—"}</div>
      </div>

      {/* changesLine 全文 */}
      <div className="mission-detail-section">
        <div className="mission-detail-label">变更说明</div>
        <div className="mission-detail-value">{lane.changesLine || "—"}</div>
      </div>

      {/* risk 明细 */}
      <div className="mission-detail-section">
        <div className="mission-detail-label">风险明细</div>
        <div className="mission-detail-row">
          <div className="mission-detail-pair">
            <span className="lbl">BLOCK:</span>
            <span className="val" style={{ color: lane.risk.blockCount > 0 ? "#e74c3c" : "#27ae60" }}>
              {lane.risk.blockCount}
            </span>
          </div>
          <div className="mission-detail-pair">
            <span className="lbl">WARN:</span>
            <span className="val" style={{ color: lane.risk.warnCount > 0 ? "#f39c12" : "#27ae60" }}>
              {lane.risk.warnCount}
            </span>
          </div>
          <div className="mission-detail-pair">
            <span className="lbl">不可逆:</span>
            <span className="val" style={{ color: lane.risk.hasIrreversibleBlock ? "#e74c3c" : "#8b949e" }}>
              {lane.risk.hasIrreversibleBlock ? "是" : "否"}
            </span>
          </div>
          <div className="mission-detail-pair">
            <span className="lbl">债务文件:</span>
            <span className="val">{lane.debtCount}</span>
          </div>
        </div>
      </div>

      {/* deviation 明细 */}
      {lane.deviation != null && (
        <div className="mission-detail-section">
          <div className="mission-detail-label">偏差分析</div>
          <div className="mission-detail-row">
            <div className="mission-detail-pair">
              <span className="lbl">计划文件数:</span>
              <span className="val">{lane.deviation.planned}</span>
            </div>
            <div className="mission-detail-pair">
              <span className="lbl">覆盖率:</span>
              <span className="val">{formatCoverage(lane.deviation.coverage)}</span>
            </div>
            <div className="mission-detail-pair">
              <span className="lbl">TrustFlag:</span>
              <span
                className="val"
                style={{
                  color: deviationNeedsAlert(lane.deviation.trustFlag)
                    ? "#e74c3c"
                    : "#27ae60",
                }}
              >
                {lane.deviation.trustFlag}
              </span>
            </div>
            <div className="mission-detail-pair">
              <span className="lbl">缺失:</span>
              <span className="val">{lane.deviation.missingCount}</span>
            </div>
            <div className="mission-detail-pair">
              <span className="lbl">计划外:</span>
              <span className="val">{lane.deviation.offPlanCount}</span>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

// ─── ReviewSidebar ────────────────────────────────────────────────────────────

interface ReviewSidebarProps {
  items: ReviewItem[];
  onOpenFile: (path: string) => void;
}

function ReviewSidebar({ items, onOpenFile }: ReviewSidebarProps) {
  return (
    <div className="mission-review-sidebar">
      <div className="mission-review-header">
        待审风险 ({items.length})
      </div>
      <div className="mission-review-list">
        {items.length === 0 ? (
          <div className="mission-review-empty">暂无待审风险</div>
        ) : (
          items.map((item) => {
            const { bg, text } = severityBadgeColor(item.severity);
            return (
              <div
                key={item.changeId}
                className="mission-review-item"
                title={item.evidence || item.filePath}
                onClick={() => onOpenFile(item.filePath)}
              >
                <div className="mission-review-item-header">
                  {isInterrupt(item) && (
                    <span className="mission-review-interrupt" title="紧急打断">
                      🚨
                    </span>
                  )}
                  <span
                    className="mission-review-sev"
                    style={{ background: bg, color: text }}
                  >
                    {item.severity}
                  </span>
                  <span className="mission-review-kind">{item.kind}</span>
                </div>
                <div className="mission-review-path" title={item.filePath}>
                  {item.filePath}
                </div>
              </div>
            );
          })
        )}
      </div>
    </div>
  );
}

// ─── MissionControlBoard ──────────────────────────────────────────────────────

/**
 * Mission Control 主视图（H-P1）。
 *
 * 顶部：summary 条（N 泳道 / 需注意 / 风险统计 / 债务文件）。
 * 主体：左侧 N 条泳道卡片一屏并列（grid），右侧审查队列只读侧栏。
 * 点击泳道卡片→展开详情（deviation/risk 明细/全文）。
 */
export function MissionControlBoard() {
  const repoId = useWorkbench((s) => s.repoId);
  const openFile = useWorkbench((s) => s.openFile);
  const [data, setData] = useState<MissionControl | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [expandedLaneId, setExpandedLaneId] = useState<number | null>(null);

  const load = useCallback(async () => {
    if (!repoId) return;
    setLoading(true);
    setError(null);
    try {
      const overview = await fetchMissionOverview(repoId);
      setData(overview);
    } catch (err: unknown) {
      setError(String(err));
    } finally {
      setLoading(false);
    }
  }, [repoId]);

  useEffect(() => {
    load();
  }, [load]);

  const handleToggleLane = useCallback((laneId: number) => {
    setExpandedLaneId((prev) => (prev === laneId ? null : laneId));
  }, []);

  // ── 无仓库 ──
  if (!repoId) {
    return (
      <div className="mission-empty">
        <span className="codicon codicon-dashboard" />
        <span>请先打开仓库</span>
      </div>
    );
  }

  // ── 加载中 ──
  if (loading && !data) {
    return <div className="mission-loading">加载 Mission Control 数据…</div>;
  }

  // ── 错误 ──
  if (error) {
    return (
      <div className="mission-empty">
        <span className="codicon codicon-error" />
        <span>{error}</span>
        <button
          onClick={load}
          style={{ marginTop: 8, padding: "4px 12px", cursor: "pointer" }}
        >
          重试
        </button>
      </div>
    );
  }

  if (!data) return null;

  const { summary, lanes, reviewQueue } = data;

  // ── 空态 ──
  if (lanes.length === 0) {
    return (
      <div className="mission-view">
        <SummaryBar summary={summary} loading={loading} onRefresh={load} />
        <div className="mission-empty" style={{ flex: 1 }}>
          <span className="codicon codicon-dashboard" />
          <span>暂无 agent 活动</span>
        </div>
      </div>
    );
  }

  return (
    <div className="mission-view">
      {/* 顶部 summary 条 */}
      <SummaryBar summary={summary} loading={loading} onRefresh={load} />

      {/* 主体 */}
      <div className="mission-body">
        {/* 泳道网格 */}
        <div className="mission-lanes-area">
          <div className="mission-lanes-grid">
            {lanes.map((lane) => (
              <LaneCard
                key={lane.laneId}
                lane={lane}
                expanded={expandedLaneId === lane.laneId}
                onToggle={() => handleToggleLane(lane.laneId)}
              />
            ))}
          </div>
        </div>

        {/* 审查侧栏 */}
        <ReviewSidebar items={reviewQueue} onOpenFile={openFile} />
      </div>
    </div>
  );
}

// ─── SummaryBar ───────────────────────────────────────────────────────────────

interface SummaryBarProps {
  summary: MissionControl["summary"];
  loading: boolean;
  onRefresh: () => void;
}

function SummaryBar({ summary, loading, onRefresh }: SummaryBarProps) {
  return (
    <div className="mission-summary-bar">
      <span className="mission-summary-title">Mission Control</span>
      <span className="mission-summary-chip lanes">
        {summary.laneCount} 泳道
      </span>
      {summary.needsAttentionCount > 0 && (
        <span className="mission-summary-chip attention">
          ⚠ {summary.needsAttentionCount} 需注意
        </span>
      )}
      {summary.totalBlockRisks > 0 && (
        <span className="mission-summary-chip block">
          🔴 BLOCK {summary.totalBlockRisks}
        </span>
      )}
      {summary.totalWarnRisks > 0 && (
        <span className="mission-summary-chip warn">
          🟡 WARN {summary.totalWarnRisks}
        </span>
      )}
      {summary.redDebtFiles > 0 && (
        <span className="mission-summary-chip debt-red">
          债务红 {summary.redDebtFiles}
        </span>
      )}
      {summary.yellowDebtFiles > 0 && (
        <span className="mission-summary-chip debt-yellow">
          债务黄 {summary.yellowDebtFiles}
        </span>
      )}
      <span className="mission-summary-spacer" />
      <button
        className="mission-refresh-btn"
        onClick={onRefresh}
        disabled={loading}
        title="刷新 Mission Control 数据"
      >
        {loading ? "刷新中…" : "⟳ 刷新"}
      </button>
    </div>
  );
}
