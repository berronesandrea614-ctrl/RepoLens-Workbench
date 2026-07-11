import { http } from "./http";
import { AgentRunMeta, AgentRunTrace, ChangeGraph } from "../types/agentRun";

/** 列出仓库的 Agent 执行记录（最新在前）；可选按会话过滤。 */
export async function listAgentRuns(
  repoId: number,
  sessionId?: number,
): Promise<AgentRunMeta[]> {
  const params = sessionId != null ? { sessionId } : undefined;
  return (await http.get(`/api/repos/${repoId}/agent-runs`, {
    params,
  })) as unknown as AgentRunMeta[];
}

/** 拉取单次执行的完整轨迹（run 元数据 + 步骤列表）。 */
export async function fetchAgentRunTrace(
  repoId: number,
  runId: number,
): Promise<AgentRunTrace> {
  return (await http.get(
    `/api/repos/${repoId}/agent-runs/${runId}/trace`,
  )) as unknown as AgentRunTrace;
}

/** 取某次 agent run 的改动影响面图（被改符号 + 上下游调用图）。 */
export async function fetchChangeGraph(
  repoId: number,
  runId: number,
): Promise<ChangeGraph> {
  return (await http.get(
    `/api/repos/${repoId}/agent-runs/${runId}/change-graph`,
  )) as unknown as ChangeGraph;
}
