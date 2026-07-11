import type { ConstraintViolation } from "./reconciliationTypes";
import { getBlockViolations, getConstraintIcon, fileBaseName } from "./reconciliationUtils";

interface ConstraintBadgeProps {
  violations?: ConstraintViolation[];
}

/**
 * Renders BLOCK-severity constraint violations from AGENTS.md as a red-highlighted list.
 * WARN violations are omitted to reduce noise. SEMANTIC rules never reach here (checkable=false).
 */
export function ConstraintBadge({ violations }: ConstraintBadgeProps) {
  const blockViolations = getBlockViolations(violations);
  if (blockViolations.length === 0) return null;

  return (
    <section className="recon-section recon-section--danger" aria-label="约束违规">
      <div className="recon-section-title">⛔ 约束违规（AGENTS.md）</div>
      <div role="list">
        {blockViolations.map((v, i) => (
          <div key={i} role="listitem" className="recon-constraint-row">
            <span className="recon-constraint-icon">{getConstraintIcon(v.ruleType)}</span>
            <span className="recon-constraint-rule">{v.rawText}</span>
            {v.matchedFiles.length > 0 && (
              <span className="recon-constraint-files">
                {v.matchedFiles.map(fileBaseName).join(", ")}
              </span>
            )}
          </div>
        ))}
      </div>
    </section>
  );
}
