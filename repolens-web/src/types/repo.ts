export type RepoIndexStatus = 'PENDING' | 'INDEXING' | 'INDEXED' | 'FAILED';

export type TaskStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'WAIT_RETRY' | 'FAILED';

export type TaskType =
  | 'CLONE_REPO'
  | 'PARSE_CODE'
  | 'BUILD_CHUNK'
  | 'VECTORIZE_CHUNK'
  | 'EMBED_CHUNK'
  | 'UPSERT_VECTOR'
  | 'REINDEX_REPO';

export interface CreateRepoPayload {
  workspaceId: number;
  repoName: string;
  repoUrl: string;
  branchName?: string;
}

export interface Repo {
  id: number;
  workspaceId: number;
  repoName: string;
  repoUrl: string;
  branchName: string;
  latestCommitId?: string;
  indexStatus: RepoIndexStatus;
  createdAt?: string;
  updatedAt?: string;
}

export interface IndexTask {
  id: number;
  repoId: number;
  taskType: TaskType;
  status: TaskStatus;
  retryCount: number;
  maxRetry: number;
  idempotentKey?: string;
  errorMsg?: string;
  createdAt?: string;
  updatedAt?: string;
}
