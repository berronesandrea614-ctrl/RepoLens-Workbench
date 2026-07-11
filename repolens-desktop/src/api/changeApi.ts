import { http } from "./http";
import { FileChangeDetail } from "../types/change";

/** 拉取某会话在该仓库内产生的全部文件改动详情（含前后内容、撤销状态）。 */
export async function fetchChanges(
  repoId: number,
  sessionId: number,
): Promise<FileChangeDetail[]> {
  return (await http.get(`/api/repos/${repoId}/changes`, {
    params: { sessionId },
  })) as unknown as FileChangeDetail[];
}

/**
 * 拉取仓库内全部文件改动详情（不按 sessionId 过滤）。
 * 供 RequirementInsightCard 按 changeId 查找单条改动 diff 使用。
 * 后端 GET /api/repos/{repoId}/changes 的 sessionId 参数为可选。
 */
export async function fetchAllChanges(
  repoId: number,
): Promise<FileChangeDetail[]> {
  return (await http.get(`/api/repos/${repoId}/changes`)) as unknown as FileChangeDetail[];
}

/** 应用一次提议改动：后端把新内容写入磁盘，状态 → APPLIED。
 *  ack=true 表示前端已勾选「不可逆操作」确认框，后端将跳过拦截直接落盘。 */
export async function applyChange(
  repoId: number,
  changeId: number,
  ack = false,
): Promise<void> {
  await http.post(`/api/repos/${repoId}/changes/${changeId}/apply`, undefined, {
    params: { ack },
  });
}

/** 拒绝一次提议改动：状态 → REJECTED，不写盘。 */
export async function rejectChange(repoId: number, changeId: number): Promise<void> {
  await http.post(`/api/repos/${repoId}/changes/${changeId}/reject`);
}

/** 应用该会话内所有提议改动（全部落盘）。
 *  ack=true 表示前端已逐条确认破坏性操作，后端将跳过拦截直接落盘。 */
export async function applyAllChanges(
  repoId: number,
  sessionId: number,
  ack = false,
): Promise<void> {
  await http.post(`/api/repos/${repoId}/changes/apply-all`, undefined, {
    params: { sessionId, ack },
  });
}

/** 拒绝该会话内所有提议改动（不写盘）。 */
export async function rejectAllChanges(repoId: number, sessionId: number): Promise<void> {
  await http.post(`/api/repos/${repoId}/changes/reject-all`, undefined, {
    params: { sessionId },
  });
}

/** 撤销一次已应用的改动：后端把旧内容写回磁盘，状态 → REVERTED。 */
export async function revertChange(repoId: number, changeId: number): Promise<void> {
  await http.post(`/api/repos/${repoId}/changes/${changeId}/revert`);
}
