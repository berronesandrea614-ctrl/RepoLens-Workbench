import { http } from "./http";
import type { ReconciliationVO } from "../features/requirement/insight/reconciliationTypes";

/**
 * GET 某需求的对账结果（惰性：有快照直接返回，否则计算后存快照）。
 * Feature B P1 — 全确定性，不依赖 LLM。
 */
export async function fetchReconciliation(
  repoId: number,
  requirementId: number,
): Promise<ReconciliationVO> {
  return (await http.get(
    `/api/repos/${repoId}/requirements/${requirementId}/reconciliation`,
  )) as unknown as ReconciliationVO;
}

/**
 * POST 强制重算对账结果（apply/revert 改动后调用）。
 */
export async function recomputeReconciliation(
  repoId: number,
  requirementId: number,
): Promise<ReconciliationVO> {
  return (await http.post(
    `/api/repos/${repoId}/requirements/${requirementId}/reconciliation/recompute`,
    {},
  )) as unknown as ReconciliationVO;
}
