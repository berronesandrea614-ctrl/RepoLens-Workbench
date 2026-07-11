import type { InsightFooterData } from "./insightTypes";

interface InsightFooterProps {
  footer?: InsightFooterData | null;
  onViewSubgraph?: () => void;
}

export function InsightFooter({ footer, onViewSubgraph }: InsightFooterProps) {
  return (
    <div className="ins-footer">
      {footer?.plannedDone && (
        <span className="cov">✓ 计划 {footer.plannedDone} 步落地</span>
      )}
      {footer && footer.offPlanPending > 0 && (
        <span>🚩 {footer.offPlanPending} 处计划外改动待确认</span>
      )}
      {footer?.impactNote && <span>{footer.impactNote}</span>}
      {onViewSubgraph && (
        <button className="ins-subgraph-link" onClick={onViewSubgraph}>
          查看调用子图 →
        </button>
      )}
    </div>
  );
}
