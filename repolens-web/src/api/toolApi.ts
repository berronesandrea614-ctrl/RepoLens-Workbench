import { apiPost } from './request';
import type { ToolInvokePayload, ToolName } from '../types/tool';

// POST /api/repos/{id}/tools/{toolName}/invoke：调用后端只读工具白名单入口。
export function invokeTool(repoId: number, toolName: ToolName, payload: ToolInvokePayload) {
  return apiPost<unknown, ToolInvokePayload>(`/api/repos/${repoId}/tools/${toolName}/invoke`, payload);
}
