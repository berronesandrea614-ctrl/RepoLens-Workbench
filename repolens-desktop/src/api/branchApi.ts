import { http } from "./http";

// ── Domain types ───────────────────────────────────────────────────

export interface BranchNodeMetrics {
  filesChanged: number;
  blastRadiusSize: number;
  debtDelta: number;
  confidence: number;
  verified?: boolean;
}

export type BranchStatus = "GENERATING" | "READY" | "SELECTED" | "DISCARDED";

export interface BranchNode {
  id: string;
  branchId: string;
  variantIndex: number;
  parentBranchId?: string | null;
  agentRunId?: number | null;
  label: string;
  approach: string;
  strategyHint?: string | null;
  status: BranchStatus;
  metrics: BranchNodeMetrics;
  degraded?: boolean;
}

export interface BranchGraph {
  sessionId: number;
  question: string;
  nodes: BranchNode[];
}

export interface FileChangeSummary {
  filePath: string;
  changeStatus: string;
}

// ── API functions ──────────────────────────────────────────────────

/** POST /api/repos/{repoId}/branches/fanout — 并行生成 N 个变体分支。 */
export async function fanout(
  repoId: number,
  sessionId: number,
  question: string,
  variantCount: number,
  strategies?: string[],
): Promise<BranchGraph> {
  return (await http.post(`/api/repos/${repoId}/branches/fanout`, {
    sessionId,
    question,
    variantCount,
    ...(strategies ? { strategies } : {}),
  })) as unknown as BranchGraph;
}

/** GET /api/repos/{repoId}/branches?sessionId= — 拉取当前 session 分支图。 */
export async function fetchBranchGraph(repoId: number, sessionId: number): Promise<BranchGraph> {
  return (await http.get(`/api/repos/${repoId}/branches`, {
    params: { sessionId },
  })) as unknown as BranchGraph;
}

/** POST /api/repos/{repoId}/branches/{branchId}/select — 采纳一个分支（落盘）。 */
export async function selectBranch(
  repoId: number,
  branchId: string,
  sessionId: number,
  ack: boolean,
): Promise<FileChangeSummary[]> {
  return (await http.post(`/api/repos/${repoId}/branches/${branchId}/select`, {
    sessionId,
    ack,
  })) as unknown as FileChangeSummary[];
}
