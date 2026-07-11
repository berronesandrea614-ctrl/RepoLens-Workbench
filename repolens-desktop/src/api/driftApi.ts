import { http } from "./http";

/**
 * M9 架构漂移 API：内核沿时间抓调用图快照、用图哈希当结构指纹，
 * 相邻快照两两比对得出漂移。桥接后端 KernelDriftController。
 */

/** 一次调用图快照：seq 单调递增，graphHash 是整图结构指纹，prevHash 指向上一快照。 */
export interface SnapshotView {
  snapshotId: number;
  repoId: number;
  sessionId: number | null;
  seq: number;
  label: string | null;
  commitRef: string | null;
  nodeCount: number;
  edgeCount: number;
  fileCount: number;
  graphHash: string;
  prevHash: string | null;
}

/** 单条漂移：driftType 形如 NODE_ADDED / EDGE_REMOVED / FILE_CHANGED，entityKeyHash 是语义稳定 key。 */
export interface DriftItem {
  driftType: string;
  entityKeyHash: string;
  entityDesc: string;
  filePath: string | null;
  language: string | null;
  attributedSessionId: number | null;
  attributedCommit: string | null;
}

/** 两个快照之间的漂移报告；changed=false 表示这段时间结构指纹一致，架构稳定。 */
export interface DriftReport {
  fromSnapshotId: number;
  toSnapshotId: number;
  fromHash: string;
  toHash: string;
  changed: boolean;
  drifts: DriftItem[];
}

/** 演化时间线：全部快照（seq 升序）+ 相邻快照两两比对得到的 transitions。 */
export interface EvolutionTimeline {
  repoId: number;
  snapshots: SnapshotView[];
  transitions: DriftReport[];
}

/** 抓一次当前调用图快照并落库；label 可选，用于给这次快照打个人类可读标签。 */
export async function captureSnapshot(repoId: number, label?: string): Promise<SnapshotView> {
  return (await http.post(`/api/repos/${repoId}/drift/snapshots`, null, {
    params: label ? { label } : {},
  })) as unknown as SnapshotView;
}

export async function fetchSnapshots(repoId: number): Promise<SnapshotView[]> {
  return (await http.get(`/api/repos/${repoId}/drift/snapshots`)) as unknown as SnapshotView[];
}

export async function fetchEvolution(repoId: number): Promise<EvolutionTimeline> {
  return (await http.get(`/api/repos/${repoId}/drift/evolution`)) as unknown as EvolutionTimeline;
}

export async function detectDrift(
  repoId: number,
  fromSnapshotId: number,
  toSnapshotId: number,
): Promise<DriftReport> {
  return (await http.post(`/api/repos/${repoId}/drift/detect`, {
    fromSnapshotId,
    toSnapshotId,
  })) as unknown as DriftReport;
}
