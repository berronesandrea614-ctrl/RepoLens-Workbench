import { http } from "./http";
import { CodeGraph } from "../types/graph";

export interface Frame {
  frameIndex: number;
  agentRunId: number;
  sessionId: number;
  createdAt: string;
  changedFilePaths: string[];
  changedFileCount: number;
  touchedSymbolCount: number;
}

export interface Timeline {
  frames: Frame[];
  frameCount: number;
  historyLimited: boolean;
}

export async function fetchTimeline(repoId: number): Promise<Timeline> {
  return (await http.get(`/api/repos/${repoId}/timeline`)) as unknown as Timeline;
}

export async function fetchFrameGraph(repoId: number, frameIndex: number): Promise<CodeGraph> {
  return (await http.get(`/api/repos/${repoId}/timeline/${frameIndex}/graph`)) as unknown as CodeGraph;
}
