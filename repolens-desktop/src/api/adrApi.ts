import { http } from "./http";

// ─── Types ────────────────────────────────────────────────────────────────────

export interface Adr {
  id: number;
  repoId: number;
  number: number | null;
  title: string;
  status: "PROPOSED" | "ACCEPTED" | "SUPERSEDED";
  context: string | null;
  decision: string | null;
  consequences: string | null;
  drivers: string[];
  options: string[];
  sourceType: string;
  sourceId: number | null;
  filePath: string | null;
  supersededBy: number | null;
  /** 后端返回 boolean 或 0/1，用 !! 强转。 */
  degraded: boolean;
  createdAt: string;
  updatedAt: string;
}

// ─── API functions ─────────────────────────────────────────────────────────────

/** 列出仓库所有 ADR。 */
export async function listAdrs(repoId: number): Promise<Adr[]> {
  const raw = (await http.get(`/api/repos/${repoId}/adrs`)) as unknown as Adr[];
  return raw.map((a) => ({ ...a, degraded: !!a.degraded }));
}

/** 获取单条 ADR 详情。 */
export async function getAdr(repoId: number, adrId: number): Promise<Adr> {
  const raw = (await http.get(`/api/repos/${repoId}/adrs/${adrId}`)) as unknown as Adr;
  return { ...raw, degraded: !!raw.degraded };
}

/** 从需求结晶生成 ADR（PROPOSED 状态）。 */
export async function generateAdr(repoId: number, requirementId: number): Promise<Adr> {
  const raw = (await http.post(`/api/repos/${repoId}/adrs/generate`, {
    requirementId,
  })) as unknown as Adr;
  return { ...raw, degraded: !!raw.degraded };
}

/** 采纳 ADR，分配编号并写入 MADR 文件。 */
export async function acceptAdr(repoId: number, adrId: number): Promise<Adr> {
  const raw = (await http.post(`/api/repos/${repoId}/adrs/${adrId}/accept`, {})) as unknown as Adr;
  return { ...raw, degraded: !!raw.degraded };
}

/** 标记一条 ADR 被另一条 ADR 取代（SUPERSEDED）。 */
export async function supersedeAdr(
  repoId: number,
  adrId: number,
  supersedingAdrId: number,
): Promise<Adr> {
  const raw = (await http.post(`/api/repos/${repoId}/adrs/${adrId}/supersede`, {
    supersedingAdrId,
  })) as unknown as Adr;
  return { ...raw, degraded: !!raw.degraded };
}

// ─── Pure utility functions (testable without HTTP) ───────────────────────────

/**
 * 将 ADR status 转为 CSS class 后缀。
 * "PROPOSED" → "proposed" / "ACCEPTED" → "accepted" / "SUPERSEDED" → "superseded"
 */
export function statusClass(status: string): string {
  switch (status?.toUpperCase()) {
    case "PROPOSED":
      return "proposed";
    case "ACCEPTED":
      return "accepted";
    case "SUPERSEDED":
      return "superseded";
    default:
      return "unknown";
  }
}

/**
 * 将 ADR status 转为中文显示标签。
 */
export function statusLabel(status: string): string {
  switch (status?.toUpperCase()) {
    case "PROPOSED":
      return "草案";
    case "ACCEPTED":
      return "已采纳";
    case "SUPERSEDED":
      return "已废弃";
    default:
      return status ?? "未知";
  }
}

/**
 * 将 ADR number 格式化为 "0001" 形式，null 时返回 "—"。
 */
export function formatAdrNumber(n: number | null): string {
  if (n == null) return "—";
  return String(n).padStart(4, "0");
}
