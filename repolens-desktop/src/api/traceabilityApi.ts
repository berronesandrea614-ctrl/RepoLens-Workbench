import { http } from "./http";
import type {
  TraceForwardVO,
  TraceMapVO,
  TraceReverseVO,
} from "../features/traceability/traceabilityTypes";

/**
 * GET 双向可追溯地图（惰性：有快照直接返回，否则按需计算）。
 * Feature C MVP.
 */
export async function fetchTraceabilityMap(repoId: number): Promise<TraceMapVO> {
  return (await http.get(
    `/api/repos/${repoId}/traceability`,
  )) as unknown as TraceMapVO;
}

/**
 * POST 强制重算双向可追溯地图。
 * Feature C MVP.
 */
export async function recomputeTraceability(repoId: number): Promise<TraceMapVO> {
  return (await http.post(
    `/api/repos/${repoId}/traceability/recompute`,
    {},
  )) as unknown as TraceMapVO;
}

/**
 * GET 正向追溯：某需求 → 全部实现符号。
 * Feature C MVP.
 */
export async function fetchForwardTrace(
  repoId: number,
  requirementId: number,
): Promise<TraceForwardVO> {
  return (await http.get(
    `/api/repos/${repoId}/requirements/${requirementId}/trace`,
  )) as unknown as TraceForwardVO;
}

/**
 * GET 反向追溯：某符号 → 全部需求。
 * Feature C P1.
 */
export async function fetchReverseTrace(
  repoId: number,
  symbolId: number,
): Promise<TraceReverseVO> {
  return (await http.get(
    `/api/repos/${repoId}/symbols/${symbolId}/trace`,
  )) as unknown as TraceReverseVO;
}
