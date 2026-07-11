import { http } from "./http";

// ─── Types ────────────────────────────────────────────────────────────────────

export interface SensitiveFile {
  id: number;
  repoId: number;
  filePath: string;
  fanIn: number;
  churn: number;
  aiRatio: number;
  /** 后端可能返回 0/1，用 !! 强转。 */
  constraintHit: boolean;
  finalScore: number;
  severity: "BLOCK" | "WARN" | "INFO";
  reason: string | null;
  signals: Record<string, number> | null;
  rankNo: number;
}

export interface AgentsMdProposal {
  currentContent: string;
  proposedContent: string;
  diffMarkdown: string;
  hasChanges: boolean;
}

// ─── API functions ─────────────────────────────────────────────────────────────

/** 列出仓库所有敏感文件。 */
export async function listSensitiveFiles(repoId: number): Promise<SensitiveFile[]> {
  const raw = (await http.get(
    `/api/repos/${repoId}/governance/sensitive-files`,
  )) as unknown as SensitiveFile[];
  return raw.map((f) => ({ ...f, constraintHit: !!f.constraintHit }));
}

/** 触发重新计算敏感文件，返回更新后的列表。 */
export async function recomputeSensitiveFiles(repoId: number): Promise<SensitiveFile[]> {
  const raw = (await http.post(
    `/api/repos/${repoId}/governance/sensitive-files/recompute`,
    {},
  )) as unknown as SensitiveFile[];
  return raw.map((f) => ({ ...f, constraintHit: !!f.constraintHit }));
}

/** 获取 AGENTS.md 增补提案（当前内容 vs 提案内容）。 */
export async function getAgentsMdProposal(repoId: number): Promise<AgentsMdProposal> {
  return (await http.get(
    `/api/repos/${repoId}/governance/agents-md/proposal`,
  )) as unknown as AgentsMdProposal;
}

// ─── Pure utility functions (testable without HTTP) ───────────────────────────

/**
 * Maps a severity level to CSS-ready color tokens.
 * BLOCK → red, WARN → amber, INFO → gray
 */
export function severityColor(severity: "BLOCK" | "WARN" | "INFO"): { bg: string; text: string } {
  switch (severity) {
    case "BLOCK":
      return { bg: "rgba(231, 76, 60, 0.18)", text: "#e74c3c" };
    case "WARN":
      return { bg: "rgba(243, 156, 18, 0.18)", text: "#f39c12" };
    case "INFO":
      return { bg: "rgba(139, 148, 158, 0.18)", text: "#8b949e" };
  }
}

/**
 * Normalizes a raw signal value to [0, 1].
 * Domain-specific thresholds:
 *   fanIn      → divide by 20  (20 callers = fully hot)
 *   churn      → divide by 50  (50 commits = fully hot)
 *   aiRatio    → already [0,1]
 *   constraintHit → boolean 0 or 1
 *   unknown    → clamp to [0,1]
 */
export function normalizeSensitiveSignal(key: string, value: number): number {
  switch (key) {
    case "fanIn":
      return Math.min(value / 20.0, 1);
    case "churn":
      return Math.min(value / 50.0, 1);
    case "aiRatio":
      return Math.min(Math.max(value, 0), 1);
    case "constraintHit":
      return value > 0 ? 1 : 0;
    default:
      return Math.min(Math.max(value, 0), 1);
  }
}

/**
 * Returns the [0,1] display value for a signal key.
 *
 * The backend stores *already-normalized* [0,1] values in the `signals` map
 * (fanIn/churn/aiRatio/constraintHit).  When a value is present there, we
 * clamp it to [0,1] and return it directly — no further normalization.
 *
 * Only when the signals map has no entry for the key do we fall back to the
 * raw SensitiveFile field (fanIn count, churn count, etc.) and call
 * normalizeSensitiveSignal to convert it to [0,1].
 */
export function getSignalNorm(file: SensitiveFile, key: string): number {
  const fromSignals = file.signals?.[key];
  if (fromSignals !== undefined) {
    // Already normalized by the backend — clamp only
    return Math.min(Math.max(fromSignals, 0), 1);
  }
  // Fallback: raw field value → needs domain normalization
  let raw: number;
  switch (key) {
    case "fanIn":
      raw = file.fanIn;
      break;
    case "churn":
      raw = file.churn;
      break;
    case "aiRatio":
      raw = file.aiRatio;
      break;
    case "constraintHit":
      raw = file.constraintHit ? 1 : 0;
      break;
    default:
      raw = 0;
  }
  return normalizeSensitiveSignal(key, raw);
}
