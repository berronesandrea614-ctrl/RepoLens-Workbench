import { http } from "./http";

// ─────────────────────────────── Types ───────────────────────────────────────

/** 依赖体检结论枚举（优先级：MALICIOUS > TYPOSQUAT > NOT_FOUND > VULNERABLE > OK > UNKNOWN）。 */
export type DependencyVerdict =
  | "OK"
  | "NOT_FOUND"
  | "TYPOSQUAT"
  | "UNKNOWN"
  | "MALICIOUS"
  | "VULNERABLE";

/** 单条依赖体检结果。对应后端 DependencyCheckVO。 */
export interface DependencyCheckResult {
  id: number;
  repoId: number;
  sessionId: number | null;
  changeId: number | null;
  filePath: string;
  /** 生态系统：npm / pypi / maven */
  ecosystem: string;
  packageName: string;
  version: string | null;
  /** 提取来源：MANIFEST / IMPORT */
  source: string;
  /** 体检结论 */
  verdict: DependencyVerdict;
  /** JSON 字符串，TYPOSQUAT 时含 suggestion/distance；NOT_FOUND 时含 ecosystem/packageName */
  detailJson: string | null;
  checkedAt: string;
  /** True when this result was produced in OFFLINE mode (no network calls). */
  checkedOffline: boolean;
}

/** 解析后的 TYPOSQUAT detail 信息。 */
export interface TyposquatDetail {
  suggestion?: string;
  distance?: number;
}

/** POST 请求体：changeIds 和 sessionId 二选一，changeIds 优先。 */
export interface DependencyCheckRequest {
  changeIds?: number[];
  sessionId?: number;
}

// ──────────────────────────── Helpers ────────────────────────────────────────

/** 解析 detailJson 为 TyposquatDetail，解析失败静默返回 {}。 */
export function parseTyposquatDetail(detailJson: string | null): TyposquatDetail {
  if (!detailJson) return {};
  try {
    return JSON.parse(detailJson) as TyposquatDetail;
  } catch {
    return {};
  }
}

/**
 * Returns a human-readable label for the given verdict.
 * Used for rendering dep-flag badges in ChangesCard.
 */
export function getVerdictLabel(verdict: DependencyVerdict): string {
  switch (verdict) {
    case "MALICIOUS": return "☠ 恶意";
    case "TYPOSQUAT": return "⚠ 疑似抢注";
    case "NOT_FOUND": return "⛔ 不存在";
    case "VULNERABLE": return "⚠ 有漏洞";
    case "OK": return "✅ 安全";
    case "UNKNOWN": return "? 未知";
    default: return verdict;
  }
}

/**
 * Maps a verdict to a CSS severity suffix used for dep-flag-* classes.
 * E.g. "critical" → class "dep-flag-critical"
 */
export function getVerdictSeverity(
  verdict: DependencyVerdict,
): "critical" | "warning" | "notfound" | "typosquat" | "ok" | "unknown" {
  switch (verdict) {
    case "MALICIOUS": return "critical";
    case "TYPOSQUAT": return "typosquat";
    case "NOT_FOUND": return "notfound";
    case "VULNERABLE": return "warning";
    case "OK": return "ok";
    default: return "unknown";
  }
}

// ──────────────────────────── API calls ──────────────────────────────────────

/**
 * 触发对指定 changeIds 或 sessionId 的依赖体检（落库），返回结果列表。
 * 仅发送包名到公共 registry，不含源码或文件路径。
 */
export async function checkDependencies(
  repoId: number,
  request: DependencyCheckRequest,
): Promise<DependencyCheckResult[]> {
  return (await http.post(
    `/api/repos/${repoId}/dependency-check`,
    request,
  )) as unknown as DependencyCheckResult[];
}

/**
 * 查询某会话已落库的体检结果（不触发新体检）。
 */
export async function getDependencyChecks(
  repoId: number,
  sessionId: number,
): Promise<DependencyCheckResult[]> {
  return (await http.get(`/api/repos/${repoId}/dependency-check`, {
    params: { sessionId },
  })) as unknown as DependencyCheckResult[];
}
