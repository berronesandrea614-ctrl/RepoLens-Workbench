import type { PlanItemRecon } from "./reconciliationTypes";
import { getPlanItemIcon } from "./reconciliationUtils";

interface PlanItemRowProps {
  item: PlanItemRecon;
}

/**
 * 单行计划步对账行（四态：LANDED/PARTIAL/MISSING_ATTEMPTED/MISSING_SILENT）。
 * 克制原则：只有🚩 MISSING_SILENT 亮红色背景。
 */
export function PlanItemRow({ item }: PlanItemRowProps) {
  const { icon, cls } = getPlanItemIcon(item.status);
  const isMissingSilent = item.status === "MISSING_SILENT";

  return (
    <div
      className={`recon-plan-item ${cls}${isMissingSilent ? " recon-plan-item--danger" : ""}`}
      role="listitem"
    >
      <span className="recon-plan-icon">{icon}</span>
      <span className="recon-plan-title">{item.title}</span>
      {item.declaredOp && (
        <span className="recon-plan-op">{item.declaredOp}</span>
      )}
      <span className="recon-plan-files">
        {item.landedFiles.length > 0 && (
          <span className="recon-plan-landed">
            ✓ {item.landedFiles.join(", ")}
          </span>
        )}
        {item.missingFiles.length > 0 && (
          <span className="recon-plan-missing">
            &nbsp;✗ {item.missingFiles.join(", ")}
          </span>
        )}
      </span>
      {item.status === "MISSING_ATTEMPTED" && (
        <span className="recon-plan-hint">（读过但未改）</span>
      )}
    </div>
  );
}
