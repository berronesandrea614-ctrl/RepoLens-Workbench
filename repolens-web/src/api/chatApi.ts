import { apiPost } from './request';
import type { CodeAnswer, CodeAnswerPayload } from '../types/chat';

// POST /api/repos/{id}/chat/answer：基于 RAG 证据生成带文件和行号引用的代码回答。
export function answerCodeQuestion(repoId: number, payload: CodeAnswerPayload) {
  return apiPost<CodeAnswer, CodeAnswerPayload>(`/api/repos/${repoId}/chat/answer`, payload);
}
