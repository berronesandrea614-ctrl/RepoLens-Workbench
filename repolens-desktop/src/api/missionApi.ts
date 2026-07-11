import { http } from "./http";

// ─── Types ────────────────────────────────────────────────────────────────────

export interface MissionSummary {
  laneCount: number;
  totalBlockRisks: number;
  totalWarnRisks: number;
  redDebtFiles: number;
  yellowDebtFiles: number;
  needsAttentionCount: number;
}

export interface Deviation {
  planned: number;
  coverage: number; // 0-100 int
  trustFlag: string;
  missingCount: number;
  offPlanCount: number;
}

export interface AgentLane {
  laneId: number;
  engine: string;
  status: string;
  claimedSuccess: boolean | null;
  claimedVerified: boolean | null;
  planLine: string;
  changesLine: string;
  changedFileCount: number;
  deviation: Deviation | null;
  debtCount: number;
  risk: {
    blockCount: number;
    warnCount: number;
    hasIrreversibleBlock: boolean;
  };
  needsAttention: boolean;
  degraded: boolean;
}

export interface ReviewItem {
  changeId: number;
  kind: string;
  reversibility: string;
  severity: string;
  interrupt: boolean;
  filePath: string;
  evidence: string;
}

export interface MissionControl {
  summary: MissionSummary;
  lanes: AgentLane[];
  reviewQueue: ReviewItem[];
}

// ─── API ─────────────────────────────────────────────────────────────────────

/** 获取 Mission Control 总览（N 条 agent 泳道 + 审查队列）。 */
export async function fetchMissionOverview(repoId: number): Promise<MissionControl> {
  return (await http.get(
    `/api/repos/${repoId}/mission-control/overview`,
  )) as unknown as MissionControl;
}
