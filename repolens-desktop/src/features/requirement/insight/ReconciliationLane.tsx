import { useEffect, useState } from "react";
import type { ReconciliationVO } from "./reconciliationTypes";
import { fetchReconciliation, recomputeReconciliation } from "../../../api/reconciliationApi";
import {
  shouldShowReconciliation,
  buildReconChips,
  getSilentAdds,
  getOverScopes,
} from "./reconciliationUtils";
import { PlanItemRow } from "./PlanItemRow";
import { OffPlanRow } from "./OffPlanRow";
import { SelfReportBanner } from "./SelfReportBanner";
import { ConstraintBadge } from "./ConstraintBadge";

interface ReconciliationLaneProps {
  repoId: number;
  requirementId: number;
}

/**
 * 对账泳道 — 展示计划项四态、计划外改动、自报可信度。
 * 置于 DeviationBanner 之后，纯展示层，不影响现有交互。
 */
export function ReconciliationLane({ repoId, requirementId }: ReconciliationLaneProps) {
  const [vo, setVo] = useState<ReconciliationVO | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [recomputing, setRecomputing] = useState(false);
  const [collapsed, setCollapsed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    fetchReconciliation(repoId, requirementId)
      .then((data) => { if (!cancelled) setVo(data); })
      .catch((e: unknown) => {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [repoId, requirementId]);

  async function handleRecompute() {
    setRecomputing(true);
    try {
      const data = await recomputeReconciliation(repoId, requirementId);
      setVo(data);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setRecomputing(false);
    }
  }

  if (loading) {
    return (
      <div className="recon-lane recon-lane--loading">
        <span className="recon-lane-spinner" />
        <span>加载对账…</span>
      </div>
    );
  }

  if (error) {
    return (
      <div className="recon-lane recon-lane--error">
        ⚠ 对账加载失败：{error}
      </div>
    );
  }

  if (!vo || !shouldShowReconciliation(vo)) return null;

  const chips = buildReconChips(vo);
  const silentAdds = getSilentAdds(vo.offPlan);
  const overScopes = getOverScopes(vo.offPlan);
  const planItems = vo.items ?? [];

  return (
    <div className="recon-lane">
      {/* ── Header ── */}
      <div className="recon-lane-header">
        <button
          className="recon-lane-toggle"
          onClick={() => setCollapsed((p) => !p)}
          aria-expanded={!collapsed}
        >
          {collapsed ? "▶" : "▼"} <span className="recon-lane-title">对账泳道</span>
        </button>

        {/* Chips overview */}
        <div className="recon-chips">
          {chips.map((c, i) => (
            <span key={i} className={`ins-chip ${c.cls}`}>{c.text}</span>
          ))}
        </div>

        <button
          className="recon-recompute-btn"
          onClick={() => void handleRecompute()}
          disabled={recomputing}
          title="强制重算对账（改动已 apply/revert 后使用）"
        >
          {recomputing ? "重算中…" : "↺ 重算"}
        </button>
      </div>

      {/* ── Body (collapsible) ── */}
      {!collapsed && (
        <div className="recon-lane-body">
          {/* Plan items */}
          {planItems.length > 0 && (
            <section className="recon-section">
              <div className="recon-section-title">计划步对账</div>
              <div role="list">
                {planItems.map((item) => (
                  <PlanItemRow key={item.stepId} item={item} />
                ))}
              </div>
            </section>
          )}

          {/* Silent adds (most critical) */}
          {silentAdds.length > 0 && (
            <section className="recon-section recon-section--danger">
              <div className="recon-section-title">🚩 静默新增（计划外高危）</div>
              <div role="list">
                {silentAdds.map((c, i) => (
                  <OffPlanRow key={i} change={c} />
                ))}
              </div>
            </section>
          )}

          {/* Over-scope changes */}
          {overScopes.length > 0 && (
            <section className="recon-section recon-section--warn">
              <div className="recon-section-title">🟠 超范围改动</div>
              <div role="list">
                {overScopes.map((c, i) => (
                  <OffPlanRow key={i} change={c} />
                ))}
              </div>
            </section>
          )}

          {/* Constraint violations from AGENTS.md */}
          <ConstraintBadge violations={vo.violations} />

          {/* Self-report */}
          {vo.selfReport && (
            <SelfReportBanner selfReport={vo.selfReport} />
          )}
        </div>
      )}
    </div>
  );
}
