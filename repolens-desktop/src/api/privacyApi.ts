import { http } from "./http";

/** 隐私模式枚举 */
export type PrivacyMode = "LOCAL_ONLY" | "ALLOWLIST" | "OPEN";

// ─── G-P2: 校验与报告类型 ──────────────────────────────────────────────────

/** 单项校验结果（PASS/FAIL + 原因）。 */
export interface VerifyCheckResult {
  passed: boolean;
  reason: string;
}

/** 四项链路校验结果 VO（对应后端 PrivacyVerifyVO）。 */
export interface PrivacyVerifyResult {
  mode: string;
  llmProviderIsLocal: VerifyCheckResult;
  baseUrlIsLoopback: VerifyCheckResult;
  ollamaReachable: VerifyCheckResult;
  recentEgressAllExternalBlocked: VerifyCheckResult;
  /** true = 全部通过且模式为 LOCAL_ONLY。 */
  verdict: boolean;
  note: string;
  checkedAt: string;
  warnings: string[];
}

/** 隐私状态 VO（对应后端 PrivacyStatusVO）。 */
export interface PrivacyStatus {
  mode: PrivacyMode;
  totalCount: number;
  blockedCount: number;
  allowedCount: number;
  /** 当前白名单（ALLOWLIST 模式有效，逗号分隔；null 代表未配置）。 */
  allowlist?: string | null;
}

/** 出网记录 VO（对应后端 EgressLogVO）。 */
export interface EgressLogEntry {
  id: number;
  ts: string;
  purpose: string;
  destHost: string;
  destPort: number | null;
  resolvedIp: string | null;
  loopback: boolean;
  /** false = 被拦截（前端标红"已拦截"）。 */
  allowed: boolean;
  privacyMode: string;
  modelName: string | null;
  bytesOut: number | null;
}

export interface UpdatePrivacyModeRequest {
  mode: PrivacyMode;
  allowlist?: string;
}

export async function getPrivacyStatus(): Promise<PrivacyStatus> {
  return (await http.get("/api/privacy/status")) as unknown as PrivacyStatus;
}

export async function getEgressLogs(limit = 50): Promise<EgressLogEntry[]> {
  return (await http.get(
    `/api/privacy/egress?limit=${limit}`
  )) as unknown as EgressLogEntry[];
}

export async function updatePrivacyMode(
  req: UpdatePrivacyModeRequest
): Promise<PrivacyStatus> {
  return (await http.put(
    "/api/privacy/mode",
    req
  )) as unknown as PrivacyStatus;
}

/** 一键校验本地链路（G-P2）：调用 /api/privacy/verify 返回四项检查结果。 */
export async function verifyPrivacyChain(): Promise<PrivacyVerifyResult> {
  return (await http.get("/api/privacy/verify")) as unknown as PrivacyVerifyResult;
}

/**
 * 下载零出网证明报告（G-P2）。
 * format="json" 获取结构化 JSON；format="txt" 下载可存档的文本证明。
 */
export async function downloadPrivacyReport(format: "json" | "txt" = "txt"): Promise<void> {
  // For text download, use native fetch to handle blob/content-disposition
  const token = localStorage.getItem("repolens.token") ?? "";
  const resp = await fetch(`/api/privacy/report?format=${format}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  if (!resp.ok) {
    throw new Error(`导出失败: HTTP ${resp.status}`);
  }
  if (format === "txt") {
    const blob = await resp.blob();
    const disposition = resp.headers.get("Content-Disposition") ?? "";
    const fileMatch = disposition.match(/filename="([^"]+)"/);
    const filename = fileMatch ? fileMatch[1] : "repolens-privacy-proof.txt";
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
  }
}

// ─── Pure utility functions (testable) ───────────────────────────────────────

/** 隐私模式徽标文案（用于 StatusBar 常驻显示）。 */
export function getPrivacyBadgeLabel(mode: PrivacyMode | string): string {
  switch (mode) {
    case "LOCAL_ONLY":
      return "🔒 本地 · 代码0出网";
    case "ALLOWLIST":
      return "🛡 白名单";
    case "OPEN":
      return "☁ 开放 · 已记录";
    default:
      return "☁ 开放 · 已记录";
  }
}

/** 隐私模式徽标 CSS 类（用于颜色区分）。 */
export function getPrivacyBadgeClass(mode: PrivacyMode | string): string {
  switch (mode) {
    case "LOCAL_ONLY":
      return "privacy-badge--local";
    case "ALLOWLIST":
      return "privacy-badge--allowlist";
    case "OPEN":
    default:
      return "privacy-badge--open";
  }
}

/** 出网记录的用途中文标签。 */
export function getPurposeLabel(purpose: string): string {
  switch (purpose) {
    case "LLM":
      return "AI 对话";
    case "EMBEDDING":
      return "向量嵌入";
    case "GIT_CLONE":
      return "Git 克隆";
    case "DEP_CHECK":
      return "依赖体检";
    default:
      return purpose;
  }
}

// ─── G-P2 pure utility functions (testable) ──────────────────────────────────

/** 将单项 VerifyCheckResult 转为展示用图标（✓/✗）。 */
export function checkIcon(c: VerifyCheckResult | undefined): string {
  if (!c) return "—";
  return c.passed ? "✓" : "✗";
}

/** 将单项 VerifyCheckResult 转为展示用 CSS class。 */
export function checkClass(c: VerifyCheckResult | undefined): string {
  if (!c) return "verify-check--unknown";
  return c.passed ? "verify-check--pass" : "verify-check--fail";
}

/** 将四项检查结果聚合为人可读摘要：全通过返回"全部通过"，否则列出失败项名称。 */
export function summarizeVerifyResult(r: PrivacyVerifyResult): string {
  if (r.verdict) return "全部通过 ✓ — 应用层0出网验证通过（LOCAL_ONLY）";
  const failed: string[] = [];
  if (!r.llmProviderIsLocal?.passed) failed.push("LLM provider 非本地");
  if (!r.baseUrlIsLoopback?.passed) failed.push("baseUrl 非回环");
  if (!r.ollamaReachable?.passed) failed.push("Ollama 不可达");
  if (!r.recentEgressAllExternalBlocked?.passed) failed.push("出网日志有外网放行");
  if (failed.length === 0) failed.push("模式不是 LOCAL_ONLY");
  return "校验未通过：" + failed.join("、");
}
