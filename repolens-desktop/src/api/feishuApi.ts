/**
 * feishuApi.ts — Feishu binding management + PTY bridge.
 *
 * Endpoints (all under /api/repos/{repoId}/feishu/):
 *   GET    /bindings          → FeishuBinding[]
 *   POST   /bindings          → FeishuBinding   (body: CreateBindingBody)
 *   DELETE /bindings/{id}     → void
 *   POST   /test-connection   → boolean         (body: { appId, appSecret })
 *   POST   /pty-output        → void            (body: { chunk })
 */

import { http } from "./http";

// ─── Types ────────────────────────────────────────────────────────────────────

export interface FeishuBinding {
  id: number;
  repoId: number;
  sessionId: string | null;
  botName: string;
  appId: string;
  status: "CONNECTED" | "DISCONNECTED" | "ERROR";
  lastError: string | null;
  createdAt: string;
}

export interface CreateBindingBody {
  botName: string;
  appId: string;
  appSecret: string;
}

// ─── API functions ────────────────────────────────────────────────────────────

export async function listBindings(repoId: number): Promise<FeishuBinding[]> {
  return (await http.get(
    `/api/repos/${repoId}/feishu/bindings`,
  )) as unknown as FeishuBinding[];
}

export async function createBinding(
  repoId: number,
  body: CreateBindingBody,
): Promise<FeishuBinding> {
  return (await http.post(
    `/api/repos/${repoId}/feishu/bindings`,
    body,
  )) as unknown as FeishuBinding;
}

export async function deleteBinding(repoId: number, id: number): Promise<void> {
  await http.delete(`/api/repos/${repoId}/feishu/bindings/${id}`);
}

export async function testConnection(
  repoId: number,
  appId: string,
  appSecret: string,
): Promise<boolean> {
  return (await http.post(
    `/api/repos/${repoId}/feishu/test-connection`,
    { appId, appSecret },
  )) as unknown as boolean;
}

export async function reportPtyOutput(
  repoId: number,
  chunk: string,
): Promise<void> {
  await http.post(`/api/repos/${repoId}/feishu/pty-output`, { chunk });
}

// ─── Pure utility functions (testable without HTTP) ───────────────────────────

/**
 * Map a FeishuBinding status to a CSS color string.
 * "CONNECTED" → green, "ERROR" → red, "DISCONNECTED" → gray.
 */
export function statusToColor(status: FeishuBinding["status"]): string {
  switch (status) {
    case "CONNECTED":
      return "#3fb950"; // green
    case "ERROR":
      return "#f85149"; // red
    case "DISCONNECTED":
      return "#6e7681"; // gray
    default:
      return "#6e7681";
  }
}

/**
 * Map a FeishuBinding status to a human-readable Chinese label.
 */
export function statusLabel(status: FeishuBinding["status"]): string {
  switch (status) {
    case "CONNECTED":
      return "已连接";
    case "ERROR":
      return "错误";
    case "DISCONNECTED":
      return "已断开";
    default:
      return status;
  }
}
