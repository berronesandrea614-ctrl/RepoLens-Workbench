import { http } from "./http";

/**
 * 内核 agent 实时改动的逐处 accept/reject（Cursor 式评审）。
 *
 * 对接后端 `/api/repos/{repoId}/agent/changes/*`（bridge KernelReviewController）：
 * accept = 影子区该文件合并回真目录（正式落地）；reject = 撤销影子区那处（真目录不动）。
 * 与旧的按 changeId 的 changeApi 不是一回事（那是非实时的提议改动流）。
 */

/** 一处待审改动。 */
export interface PendingChange {
  filePath: string;
  changeType: string;
}

/** 一次评审结果。 */
export interface ReviewResult {
  filePath: string;
  action: string;
  ok: boolean;
}

/** 列出该会话当前待审（未 accept/reject）的实时改动。 */
export async function fetchPendingChanges(
  repoId: number,
  sessionId: number,
): Promise<PendingChange[]> {
  return (await http.get(`/api/repos/${repoId}/agent/changes`, {
    params: { sessionId },
  })) as unknown as PendingChange[];
}

/** 接受单个文件的改动：影子区该文件合并回真目录。 */
export async function acceptChange(
  repoId: number,
  sessionId: number,
  filePath: string,
): Promise<ReviewResult> {
  return (await http.post(`/api/repos/${repoId}/agent/changes/accept`, {
    sessionId,
    filePath,
  })) as unknown as ReviewResult;
}

/** 拒绝单个文件的改动：撤销影子区那处，真目录不动。 */
export async function rejectChange(
  repoId: number,
  sessionId: number,
  filePath: string,
): Promise<ReviewResult> {
  return (await http.post(`/api/repos/${repoId}/agent/changes/reject`, {
    sessionId,
    filePath,
  })) as unknown as ReviewResult;
}

/** 接受该会话全部待审改动。 */
export async function acceptAllChanges(
  repoId: number,
  sessionId: number,
): Promise<ReviewResult[]> {
  return (await http.post(`/api/repos/${repoId}/agent/changes/accept-all`, {
    sessionId,
  })) as unknown as ReviewResult[];
}

/** 拒绝该会话全部待审改动。 */
export async function rejectAllChanges(
  repoId: number,
  sessionId: number,
): Promise<ReviewResult[]> {
  return (await http.post(`/api/repos/${repoId}/agent/changes/reject-all`, {
    sessionId,
  })) as unknown as ReviewResult[];
}

/**
 * 回传 askUser 反问的回复（对接 bridge AskUserController `/api/repos/{repoId}/agent/answer`）。
 * agent 挂起提问后，用户在提问卡里输入回复经此送回、唤醒挂起的 agent。
 * 返回是否成功交接（问题已超时/不存在则 false）。
 */
export async function answerAgentQuestion(
  repoId: number,
  questionId: string,
  reply: string,
): Promise<boolean> {
  return (await http.post(`/api/repos/${repoId}/agent/answer`, {
    questionId,
    reply,
  })) as unknown as boolean;
}
