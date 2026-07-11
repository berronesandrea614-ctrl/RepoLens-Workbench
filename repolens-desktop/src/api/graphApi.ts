import { http } from "./http";
import { CodeGraph } from "../types/graph";

export async function fetchGraph(
  repoId: number,
  rootSymbolId: number,
  direction: "callees" | "callers",
  depth: number,
  minConfidence: number,
): Promise<CodeGraph> {
  return (await http.get(`/api/repos/${repoId}/graph`, {
    params: { rootSymbolId, direction, depth, minConfidence },
  })) as unknown as CodeGraph;
}

export async function explainGraph(
  repoId: number,
  req: { rootLabel: string; nodes: string[]; edges: string[] },
): Promise<string> {
  return (await http.post(`/api/repos/${repoId}/graph/explain`, req)) as unknown as string;
}
