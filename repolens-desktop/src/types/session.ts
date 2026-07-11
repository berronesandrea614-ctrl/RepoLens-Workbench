import { AgentStep, CodeReference, FileChangeSummary } from "./chat";
import { SolutionSetView } from "../api/solutionApi";

/** 会话列表项（GET /sessions），后端按最新在前返回。 */
export interface ChatSessionMeta {
  id: number;
  title: string;
  messageCount: number;
  lastMessagePreview: string;
  createdAt?: string;
}

/** 历史消息（GET /sessions/{id}/messages），按时间正序返回。 */
export interface ChatMessage {
  id: number;
  role: "USER" | "ASSISTANT";
  content: string;
  references: CodeReference[];
}

/**
 * 会话线程中的一条消息（UI 态）。历史消息只带 references；
 * 新回答在流式过程中逐步补齐 agentSteps / fileChanges / 计量信息。
 */
export interface ThreadMessage {
  role: "USER" | "ASSISTANT";
  content: string;
  references?: CodeReference[];
  agentSteps?: AgentStep[];
  agentIterations?: number;
  agentToolCalls?: number;
  fileChanges?: FileChangeSummary[];
  /** 本条回答对应的 Agent 执行记录 id（可跳到执行轨迹视图）。 */
  agentRunId?: number;
  /** 本条回答所属会话（供 ChangesCard 拉取改动详情）。 */
  sessionId?: number;
  /** 本条回答附带的多方案组（M8 fanout 产物）；存在时渲染方案卡组供对比选用。 */
  solutionSet?: SolutionSetView;
  streaming?: boolean;
  degraded?: boolean;
  degradeReason?: string;
  modelName?: string;
  costMs?: number;
  promptTokens?: number;
  completionTokens?: number;
  error?: string;
}
