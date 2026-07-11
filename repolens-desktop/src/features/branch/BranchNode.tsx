import { Handle, Position, NodeProps } from "reactflow";
import { BranchNode as BranchNodeData, BranchStatus } from "../../api/branchApi";
import { formatMetrics } from "./branchHelpers";

// ── Status → CSS class ───────────────────────────────────────────
function statusClass(status: BranchStatus): string {
  switch (status) {
    case "GENERATING": return "branch-node-status-generating";
    case "READY":      return "branch-node-status-ready";
    case "SELECTED":   return "branch-node-status-selected";
    case "DISCARDED":  return "branch-node-status-discarded";
  }
}

// ── BranchMetricsBar ─────────────────────────────────────────────
interface MetricsBarProps {
  filesChanged: number;
  blastRadiusSize: number;
  debtDelta: number;
  confidence: number;
}

function BranchMetricsBar({ filesChanged, blastRadiusSize, debtDelta, confidence }: MetricsBarProps) {
  const fmt = formatMetrics({ filesChanged, blastRadiusSize, debtDelta, confidence });

  const debtClass =
    debtDelta > 0 ? "debt-positive" : debtDelta < 0 ? "debt-negative" : "";
  const confClass = confidence >= 70 ? "confidence-high" : "confidence-low";

  return (
    <div className="branch-metrics-bar">
      <span className="branch-metric-chip">
        <span className="branch-metric-key">改</span>
        <span className="branch-metric-val">{fmt.files}</span>
      </span>
      <span className="branch-metric-chip">
        <span className="branch-metric-key">影响</span>
        <span className="branch-metric-val">{fmt.blast}</span>
      </span>
      <span className="branch-metric-chip">
        <span className="branch-metric-key">债</span>
        <span className={`branch-metric-val ${debtClass}`}>{fmt.debt}</span>
      </span>
      <span className="branch-metric-chip">
        <span className="branch-metric-key">信</span>
        <span className={`branch-metric-val ${confClass}`}>{fmt.confidence}</span>
      </span>
    </div>
  );
}

// ── Custom reactflow node ────────────────────────────────────────
export type BranchNodeFlowData = BranchNodeData;

export function BranchNodeComponent({ data, selected }: NodeProps<BranchNodeFlowData>) {
  const sc = statusClass(data.status);
  return (
    <>
      {/* Parent → this node */}
      <Handle type="target" position={Position.Top} style={{ visibility: "hidden" }} />

      <div className={`branch-node ${sc} ${selected ? "selected" : ""}`}>
        <div className="branch-node-label" title={data.label}>
          {data.label}
        </div>

        {data.approach && (
          <div className="branch-node-approach" title={data.approach}>
            {data.approach}
          </div>
        )}

        {data.degraded && (
          <div className="branch-node-degraded">⚠ 自评（未落盘验证）</div>
        )}

        <BranchMetricsBar
          filesChanged={data.metrics.filesChanged}
          blastRadiusSize={data.metrics.blastRadiusSize}
          debtDelta={data.metrics.debtDelta}
          confidence={data.metrics.confidence}
        />
      </div>

      {/* This node → children */}
      <Handle type="source" position={Position.Bottom} style={{ visibility: "hidden" }} />
    </>
  );
}
