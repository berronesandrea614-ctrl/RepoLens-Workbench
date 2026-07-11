import { http } from "./http";
import { ChatMessage, ChatSessionMeta } from "../types/session";

/** 某仓库的全部会话，最新在前。 */
export async function listSessions(repoId: number): Promise<ChatSessionMeta[]> {
  return (await http.get(`/api/repos/${repoId}/sessions`)) as unknown as ChatSessionMeta[];
}

/** 某会话的全部消息，按时间正序。 */
export async function loadMessages(repoId: number, sessionId: number): Promise<ChatMessage[]> {
  return (await http.get(
    `/api/repos/${repoId}/sessions/${sessionId}/messages`,
  )) as unknown as ChatMessage[];
}

/** 删除一个会话（连同其消息）。 */
export async function deleteSession(repoId: number, sessionId: number): Promise<void> {
  await http.delete(`/api/repos/${repoId}/sessions/${sessionId}`);
}

/** 重命名会话标题。 */
export async function renameSession(
  repoId: number,
  sessionId: number,
  title: string,
): Promise<void> {
  await http.put(`/api/repos/${repoId}/sessions/${sessionId}/title`, { title });
}
