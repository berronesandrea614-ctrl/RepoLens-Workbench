import type { AgentLane, ReviewItem } from "../../api/missionApi";

// ─── Lane helpers ─────────────────────────────────────────────────────────────

/** 是否需要高亮（红边） */
export function laneNeedsHighlight(lane: AgentLane): boolean {
  return lane.needsAttention;
}

/** 偏差 trustFlag 是否需要标红（非 OK） */
export function deviationNeedsAlert(trustFlag: string | undefined): boolean {
  return trustFlag != null && trustFlag !== "OK";
}

/** 格式化覆盖率 */
export function formatCoverage(coverage: number): string {
  return `${coverage}%`;
}

/** status → 显示文本 */
export function formatStatus(status: string): string {
  switch (status) {
    case "COMPLETED": return "已完成";
    case "RUNNING":   return "运行中";
    case "FAILED":    return "失败";
    case "PENDING":   return "等待中";
    case "CANCELLED": return "已取消";
    default:          return status;
  }
}

/** status → CSS 颜色 */
export function statusColor(status: string): string {
  switch (status) {
    case "COMPLETED": return "#27ae60";
    case "RUNNING":   return "#4daafc";
    case "FAILED":    return "#e74c3c";
    case "PENDING":   return "#8b949e";
    case "CANCELLED": return "#8b949e";
    default:          return "#8b949e";
  }
}

// ─── Review helpers ───────────────────────────────────────────────────────────

/** severity → 显示颜色 */
export function severityBadgeColor(severity: string): { bg: string; text: string } {
  switch (severity) {
    case "BLOCK":
      return { bg: "rgba(231,76,60,0.2)", text: "#e74c3c" };
    case "WARN":
      return { bg: "rgba(243,156,18,0.2)", text: "#f39c12" };
    case "INFO":
    default:
      return { bg: "rgba(139,148,158,0.2)", text: "#8b949e" };
  }
}

/** 是否为紧急打断项 */
export function isInterrupt(item: ReviewItem): boolean {
  return item.interrupt === true;
}
