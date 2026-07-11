import type { ReconciliationVO, OffPlanChange, SelfReportCheck, ConstraintViolation } from "./reconciliationTypes";

/** 返回计划步状态对应的图标和 CSS class。 */
export function getPlanItemIcon(status: string): { icon: string; cls: string } {
  switch (status) {
    case "LANDED":           return { icon: "✅", cls: "recon-landed" };
    case "PARTIAL":          return { icon: "🟡", cls: "recon-partial" };
    case "MISSING_ATTEMPTED": return { icon: "🟠", cls: "recon-missing" };
    case "MISSING_SILENT":   return { icon: "🚩", cls: "recon-silent" };
    default:                 return { icon: "❓", cls: "" };
  }
}

/** 返回计划外改动分类对应的 badge 信息。 */
export function getClassificationBadge(cls: string): { label: string; severe: boolean } {
  if (cls === "SILENT_ADD")  return { label: "🚩 静默新增", severe: true };
  if (cls === "OVER_SCOPE")  return { label: "🟠 超范围",   severe: false };
  return                              { label: cls,            severe: false };
}

/** 返回自报检查项的图标。 */
export function getCheckIcon(check: SelfReportCheck): string {
  if (check.severity === "RED")    return "🔴";
  if (check.severity === "ORANGE") return "🟠";
  return "ℹ️";
}

/** 返回信任标志对应的展示文本 + CSS class。 */
export function getTrustBadge(trustFlag?: string): { label: string; cls: string } {
  if (trustFlag === "FABRICATED") return { label: "🔴 自报存疑",    cls: "recon-trust-fabricated" };
  if (trustFlag === "SUSPECT")    return { label: "🟠 部分可信",    cls: "recon-trust-suspect" };
  return                                  { label: "✅ 可信",        cls: "recon-trust-ok" };
}

/**
 * 是否要展示对账泳道？
 * 无 VO 时不展示；纯问答（hasChanges=false 且 planned=false）时隐藏。
 */
export function shouldShowReconciliation(vo: ReconciliationVO | null | undefined): boolean {
  if (!vo) return false;
  // 有任何计划内容或有改动分类时展示
  return vo.planned || (vo.offPlan?.length ?? 0) > 0 || !!vo.selfReport;
}

/**
 * 概览 chips 列表（按顺序：覆盖率、契合度、计划外计数、信任标志）。
 */
export function buildReconChips(vo: ReconciliationVO): Array<{ cls: string; text: string }> {
  const chips: Array<{ cls: string; text: string }> = [];
  const s = vo.summary;
  if (!s) return chips;

  if (vo.planned) {
    const covPct = Math.round(s.coverage * 100);
    chips.push({ cls: covPct >= 100 ? "g" : covPct >= 50 ? "y" : "r", text: `覆盖率 ${covPct}%` });
    const fidPct = Math.round(s.fidelity * 100);
    chips.push({ cls: fidPct >= 80 ? "g" : "y", text: `契合度 ${fidPct}%` });
  }

  if (s.offPlanCount > 0) {
    chips.push({ cls: "r", text: `🚩 ${s.offPlanCount} 计划外` });
  }

  if ((s.violationCount ?? 0) > 0) {
    chips.push({ cls: "r", text: `⛔ ${s.violationCount} 约束违规` });
  }

  const { label } = getTrustBadge(s.trustFlag);
  chips.push({ cls: s.trustFlag === "FABRICATED" ? "r" : s.trustFlag === "SUSPECT" ? "y" : "g", text: label });

  return chips;
}

/** 过滤出 SILENT_ADD 的改动（最高危，显示为红行）。 */
export function getSilentAdds(offPlan: OffPlanChange[]): OffPlanChange[] {
  return offPlan.filter((o) => o.classification === "SILENT_ADD");
}

/** 过滤出 OVER_SCOPE 的改动。 */
export function getOverScopes(offPlan: OffPlanChange[]): OffPlanChange[] {
  return offPlan.filter((o) => o.classification === "OVER_SCOPE");
}

/** 取文件路径的末段（文件名）。 */
export function fileBaseName(filePath: string): string {
  const slash = Math.max(filePath.lastIndexOf("/"), filePath.lastIndexOf("\\"));
  return slash >= 0 ? filePath.substring(slash + 1) : filePath;
}

/** 是否有任何 RED 级别的自报检查项。 */
export function hasRedChecks(checks?: SelfReportCheck[]): boolean {
  return (checks ?? []).some((c) => c.severity === "RED");
}

/** 过滤出 BLOCK 级别的约束违规（展示为红行）。SEMANTIC 不含在内（后端已标 checkable=false）。 */
export function getBlockViolations(violations?: ConstraintViolation[]): ConstraintViolation[] {
  return (violations ?? []).filter((v) => v.severity === "BLOCK");
}

/** 返回约束规则类型对应的图标。 */
export function getConstraintIcon(ruleType: string): string {
  switch (ruleType) {
    case "PATH_FORBIDDEN":     return "🚫";
    case "FILETYPE_FORBIDDEN": return "🚫";
    case "NO_NEW_DEP":         return "📦";
    case "MUST_VERIFY":        return "🧪";
    case "KEEP_SCOPE":         return "📐";
    default:                   return "⚠️";
  }
}
