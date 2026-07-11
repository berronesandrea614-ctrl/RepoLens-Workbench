import { apiGet, apiPost } from './request';
import type { CreateRepoPayload, IndexTask, Repo } from '../types/repo';

// POST /api/repos：创建仓库元数据，后续导入和索引都以 repoId 为入口。
export function createRepo(payload: CreateRepoPayload) {
  return apiPost<Repo, CreateRepoPayload>('/api/repos', payload);
}

// GET /api/repos/{id}：查询仓库状态，用于观察 latestCommitId 和 indexStatus。
export function getRepo(repoId: number) {
  return apiGet<Repo>(`/api/repos/${repoId}`);
}

// POST /api/repos/{id}/import：同步触发 JGit 仓库导入调试链路。
export function importRepo(repoId: number) {
  return apiPost<unknown>(`/api/repos/${repoId}/import`);
}

// POST /api/repos/{id}/parse：同步触发 JavaParser 代码解析调试链路。
export function parseRepo(repoId: number) {
  return apiPost<unknown>(`/api/repos/${repoId}/parse`);
}

// POST /api/repos/{id}/chunks/build：同步触发 code_chunk 构建调试链路。
export function buildChunks(repoId: number) {
  return apiPost<unknown>(`/api/repos/${repoId}/chunks/build`);
}

// POST /api/repos/{id}/vectors/build：同步触发 MockEmbedding + Milvus 写入调试链路。
export function buildVectors(repoId: number) {
  return apiPost<unknown>(`/api/repos/${repoId}/vectors/build`);
}

// POST /api/repos/{id}/index/async：提交 RocketMQ 多阶段异步索引流水线。
export function submitAsyncIndex(repoId: number) {
  return apiPost<unknown>(`/api/repos/${repoId}/index/async`);
}

// GET /api/repos/{id}/tasks：查询 index_task 状态机，观察异步索引流转和重试。
export function listRepoTasks(repoId: number) {
  return apiGet<IndexTask[]>(`/api/repos/${repoId}/tasks`);
}
