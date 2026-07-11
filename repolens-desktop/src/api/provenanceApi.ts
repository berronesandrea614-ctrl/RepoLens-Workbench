import { http } from "./http";

// ─── Types ────────────────────────────────────────────────────────────────────

export interface ProvenanceRecord {
  id: number;
  repoId: number;
  seq: number;
  changeId: number | null;
  llmCallId: number | null;
  agentRunId: number | null;
  provider: string | null;
  modelName: string | null;
  modelVersion: string | null;
  promptHash: string | null;
  contextHash: string | null;
  filePath: string | null;
  diffHash: string | null;
  decision: string;
  approverId: number | null;
  decidedAt: string | null;
  prevHash: string | null;
  recordHash: string | null;
  complianceNote: string | null;
}

export interface ProvenancePage {
  records: ProvenanceRecord[];
  total: number;
  page: number;
  size: number;
}

export interface ProvenanceVerifyResult {
  verified: boolean;
  brokenAtSeq: number | null;
  totalRecords: number;
  note: string;
}

// ─── API functions ─────────────────────────────────────────────────────────────

/** 分页查询溯源账本时间线。 */
export async function fetchProvenanceRecords(
  repoId: number,
  page = 0,
  size = 20,
): Promise<ProvenancePage> {
  return (await http.get(`/api/repos/${repoId}/provenance/records`, {
    params: { page, size },
  })) as unknown as ProvenancePage;
}

/** 查询单条账本记录（全链路详情）。 */
export async function fetchProvenanceRecord(
  repoId: number,
  id: number,
): Promise<ProvenanceRecord> {
  return (await http.get(
    `/api/repos/${repoId}/provenance/records/${id}`,
  )) as unknown as ProvenanceRecord;
}

/** 校验哈希链完整性。 */
export async function verifyProvenance(
  repoId: number,
): Promise<ProvenanceVerifyResult> {
  return (await http.get(
    `/api/repos/${repoId}/provenance/verify`,
  )) as unknown as ProvenanceVerifyResult;
}

/** 下载溯源账本导出链接（在新标签中直接下载）。 */
export function getExportUrl(
  repoId: number,
  format: "json" | "csv" | "aibom",
): string {
  return `/api/repos/${repoId}/provenance/export?format=${format}`;
}

// ─── Pure utility functions (testable without HTTP) ───────────────────────────

/**
 * 将 decision 字段转为显示文本。
 * "APPROVED" → "已批准", "REJECTED" → "已拒绝", "REVERTED" → "已回滚"
 */
export function formatDecision(decision: string): string {
  switch (decision?.toUpperCase()) {
    case "APPROVED":
      return "已批准";
    case "REJECTED":
      return "已拒绝";
    case "REVERTED":
      return "已回滚";
    default:
      return decision ?? "未知";
  }
}

/**
 * 根据 decision 返回 CSS class name（用于颜色区分）。
 */
export function getDecisionClass(decision: string): string {
  switch (decision?.toUpperCase()) {
    case "APPROVED":
      return "decision-approved";
    case "REJECTED":
      return "decision-rejected";
    case "REVERTED":
      return "decision-reverted";
    default:
      return "decision-unknown";
  }
}

/**
 * 将 promptHash 格式化为"指纹"显示：取前8位，空时返回占位符。
 * 历史变更（null）显示"未知(历史变更)"。
 */
export function formatPromptFingerprint(
  promptHash: string | null | undefined,
): string {
  if (promptHash == null) return "未知(历史变更)";
  if (promptHash.length === 0) return "—";
  return promptHash.slice(0, 8);
}

/**
 * 将 ISO 日期时间字符串格式化为本地时间（yyyy-MM-dd HH:mm）。
 * 空或无效时返回"—"。
 */
export function formatDecidedAt(decidedAt: string | null | undefined): string {
  if (!decidedAt) return "—";
  try {
    const d = new Date(decidedAt);
    const yyyy = d.getFullYear();
    const mm = String(d.getMonth() + 1).padStart(2, "0");
    const dd = String(d.getDate()).padStart(2, "0");
    const hh = String(d.getHours()).padStart(2, "0");
    const mi = String(d.getMinutes()).padStart(2, "0");
    return `${yyyy}-${mm}-${dd} ${hh}:${mi}`;
  } catch {
    return decidedAt;
  }
}
