/**
 * 斜杠面板数据源：拉取当前仓库下可用的斜杠项（内核 skill + 自定义命令），
 * 供聊天输入框的 `/` 面板做选择/调用（对齐 Claude Code 的 /command 面板）。
 *
 * 后端 GET /api/repos/{repoId}/agent/skills 只回 name/description/source（不回 SKILL.md 正文——
 * 正文由 agent 触发 Skill 工具时才注入）。
 */
import { http } from "./http";

export interface SlashItem {
  name: string;
  description: string;
  type: "skill" | "command";
  source: string; // builtin | personal | project
}

/**
 * 拉取斜杠项；失败返回空数组（面板降级为空，不打断输入）。
 * 注意：http 的响应拦截器已把后端 {code,message,data} 解包为 data 本身，
 * 故这里拿到的直接就是 SlashItem[]（与 treeApi/sessionApi 同一约定）。
 */
export async function fetchSlashItems(repoId: number): Promise<SlashItem[]> {
  try {
    const data = (await http.get(
      `/api/repos/${repoId}/agent/skills`,
    )) as unknown as SlashItem[];
    return data ?? [];
  } catch {
    return [];
  }
}
