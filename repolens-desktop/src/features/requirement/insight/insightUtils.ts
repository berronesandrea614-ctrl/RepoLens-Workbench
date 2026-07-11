import { FlowNode, FlowItem } from "./insightTypes";

/** Type guard: is this flow item a node (vs edge)? */
export function isFlowNode(item: FlowItem): item is FlowNode {
  return item.nodeType === "node";
}

/**
 * Can the node be clicked to open a diff or file?
 * Rules: external=true → no; cls=dim → no; needs changeId or filePath.
 */
export function isNodeClickable(node: FlowNode): boolean {
  if (node.external) return false;
  if (node.cls === "dim") return false;
  return !!(node.changeId || node.filePath);
}

/** What action should clicking a node trigger? */
export function getNodeAction(node: FlowNode): "diff" | "openFile" | "none" {
  if (!isNodeClickable(node)) return "none";
  if (node.changeId) return "diff";
  if (node.filePath) return "openFile";
  return "none";
}

/** CSS class suffix for a step based on kind. */
export function getStepCls(kind: string): string {
  if (kind === "risk") return "risk";
  if (kind === "off") return "off";
  return "";
}

/** Should the DeviationBanner be shown? */
export function shouldShowDeviation(
  deviation?: { files: string[]; note: string } | null,
): boolean {
  return !!deviation && Array.isArray(deviation.files) && deviation.files.length > 0;
}

/** Pure-ask mode: no code changes at all. */
export function isDegradeMode(vo: { hasChanges: boolean }): boolean {
  return !vo.hasChanges;
}

/** No-plan mode: code was changed but no structured plan exists. */
export function isNoplanMode(vo: { hasChanges: boolean; planned: boolean }): boolean {
  return vo.hasChanges && !vo.planned;
}

/** Format a delta string for display. Returns '' if absent. */
export function formatDelta(delta?: string | null): string {
  return delta ?? "";
}

/** Build the chip label array for InsightChips. */
export function buildChipLabels(
  chips?: {
    filesChanged: number;
    added: number;
    modified: number;
    plannedStepsDone: number;
    plannedStepsTotal: number;
    offPlanCount: number;
  } | null,
): Array<{ cls: string; text: string }> {
  if (!chips) return [];
  const out: Array<{ cls: string; text: string }> = [];
  out.push({ cls: "y", text: `改 ${chips.filesChanged} 文件` });
  if (chips.added > 0) out.push({ cls: "g", text: `+${chips.added} 新增` });
  if (chips.modified > 0) out.push({ cls: "", text: `~${chips.modified} 改` });
  if (chips.plannedStepsTotal > 0) {
    const allDone = chips.plannedStepsDone === chips.plannedStepsTotal;
    out.push({
      cls: allDone ? "g" : "y",
      text: `${allDone ? "✓" : ""} ${chips.plannedStepsDone}/${chips.plannedStepsTotal} 计划步落地`,
    });
  }
  if (chips.offPlanCount > 0) {
    out.push({ cls: "r", text: `⚠ ${chips.offPlanCount} 处计划外改动` });
  } else if (chips.plannedStepsTotal > 0) {
    out.push({ cls: "g", text: "✓ 无计划外改动" });
  }
  return out;
}
