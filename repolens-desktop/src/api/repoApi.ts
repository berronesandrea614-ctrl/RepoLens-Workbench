import { http } from "./http";

export interface RepoVO {
  id: number;
  repoName: string;
  repoUrl?: string;
  branchName?: string;
  indexStatus?: string;
}

export interface CreateRepoRequest {
  workspaceId: number;
  repoName: string;
  repoUrl: string;
  branchName: string;
}

export async function listRepos(): Promise<RepoVO[]> {
  return (await http.get("/api/repos")) as unknown as RepoVO[];
}

export async function createRepo(req: CreateRepoRequest): Promise<RepoVO> {
  return (await http.post("/api/repos", req)) as unknown as RepoVO;
}

// The import/parse/chunks-build/vectors-build endpoints take only the path id
// (user resolved from the X-User-Id header); no request body is required.
export async function importRepo(id: number): Promise<unknown> {
  return await http.post(`/api/repos/${id}/import`, undefined, { timeout: 120000 });
}

export async function parseRepo(id: number): Promise<unknown> {
  return await http.post(`/api/repos/${id}/parse`, undefined, { timeout: 180000 });
}

export async function buildChunks(id: number): Promise<unknown> {
  return await http.post(`/api/repos/${id}/chunks/build`, undefined, { timeout: 120000 });
}

export async function buildVectors(id: number): Promise<unknown> {
  return await http.post(`/api/repos/${id}/vectors/build`, undefined, { timeout: 120000 });
}

/**
 * 触发后台（非阻塞）索引：立即返回，import→parse→chunk→vector 在后端后台线程跑。
 * 用于「导入不卡」——创建仓库后 fire 一下，无需 await 整条索引流水线。
 */
export async function backgroundIndex(id: number): Promise<boolean> {
  return (await http.post(`/api/repos/${id}/index/background`, undefined)) as unknown as boolean;
}

/** 删除仓库及其全部索引数据（不可撤销）。后端 DELETE /api/repos/{id} 返回 Void。 */
export async function deleteRepo(id: number): Promise<void> {
  await http.delete(`/api/repos/${id}`);
}

/** 归一化本地文件夹路径为 createRepo 使用的 file:// url，供去重比对。 */
export function fileUrlForPath(absPath: string): string {
  const cleaned = absPath.replace(/[\\/]+$/, "").replace(/\\/g, "/");
  return "file:///" + encodeURI(cleaned);
}
