import { http } from "./http";

// ─────────────────────────────── Types ───────────────────────────────────────

export type RiskSeverity = "BLOCK" | "WARN";
export type RiskReversibility = "IRREVERSIBLE" | "REVERSIBLE";

/** 单条变更风险条目。对应后端 ChangeRisk。 */
export interface ChangeRisk {
  changeId: number;
  category: string;
  ruleCode: string;
  severity: RiskSeverity;
  reversibility: RiskReversibility;
  evidence: string;
  acknowledged: boolean;
}

// ──────────────────────────── Helpers ────────────────────────────────────────

/**
 * 计算一组 ChangeRisk 的最高风险等级：
 * - 有任何 BLOCK → "BLOCK"
 * - 有任何 WARN  → "WARN"
 * - 否则 null
 */
export function rowRiskLevel(risks: ChangeRisk[]): "BLOCK" | "WARN" | null {
  if (risks.some((r) => r.severity === "BLOCK")) return "BLOCK";
  if (risks.some((r) => r.severity === "WARN")) return "WARN";
  return null;
}

/**
 * 判断该变更行是否被锁定（有 BLOCK+IRREVERSIBLE 风险且用户未 ack）。
 * 返回 true 时「应用」按钮应 disabled。
 */
export function isBlockedRow(risks: ChangeRisk[], acked: boolean): boolean {
  return (
    !acked &&
    risks.some(
      (r) => r.severity === "BLOCK" && r.reversibility === "IRREVERSIBLE",
    )
  );
}

// ──────────────────────────── API calls ──────────────────────────────────────

/**
 * 拉取某会话在该仓库内产生的全部变更风险（BLOCK / WARN）。
 * GET /api/repos/{repoId}/changes/risk?sessionId=
 */
export async function listRisks(
  repoId: number,
  sessionId: number,
): Promise<ChangeRisk[]> {
  return (await http.get(`/api/repos/${repoId}/changes/risk`, {
    params: { sessionId },
  })) as unknown as ChangeRisk[];
}

/**
 * 标记指定变更的风险为已确认（acknowledged = true）。
 * POST /api/repos/{repoId}/changes/{changeId}/acknowledge-risk
 */
export async function acknowledgeRisk(
  repoId: number,
  changeId: number,
): Promise<void> {
  await http.post(
    `/api/repos/${repoId}/changes/${changeId}/acknowledge-risk`,
  );
}
