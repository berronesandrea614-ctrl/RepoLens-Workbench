import type { OffPlanChange } from "./reconciliationTypes";
import { getClassificationBadge, fileBaseName } from "./reconciliationUtils";

interface OffPlanRowProps {
  change: OffPlanChange;
}

/**
 * 计划外改动行 — OVER_SCOPE 显示橙色，SILENT_ADD 显示红色。
 */
export function OffPlanRow({ change }: OffPlanRowProps) {
  const badge = getClassificationBadge(change.classification);
  const name = fileBaseName(change.filePath);
  return (
    <div
      className={`recon-offplan-row${badge.severe ? " recon-offplan-row--severe" : ""}`}
      role="listitem"
    >
      <div className="recon-offplan-head">
        <span className="recon-offplan-badge">{badge.label}</span>
        <span className="recon-offplan-name" title={change.filePath}>{name}</span>
        {change.opType && (
          <span className="recon-offplan-op">{change.opType}</span>
        )}
      </div>
      {(change.summary || change.sig) && (
        <div className="recon-offplan-detail">
          {change.summary && <div className="recon-offplan-summary">{change.summary}</div>}
          {change.sig && <div className="recon-offplan-sig">{change.sig}</div>}
        </div>
      )}
    </div>
  );
}
