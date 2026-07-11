import { apiPost } from './request';
import type { RagSearchPayload, RagSearchResult } from '../types/rag';

// POST /api/repos/{id}/rag/search：只做证据召回，不经过 LLM，便于调试 RAG 命中质量。
export function searchRag(repoId: number, payload: RagSearchPayload) {
  return apiPost<RagSearchResult, RagSearchPayload>(`/api/repos/${repoId}/rag/search`, payload);
}
