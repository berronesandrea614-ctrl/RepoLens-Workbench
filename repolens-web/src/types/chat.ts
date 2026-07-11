export interface CodeAnswerPayload {
  question: string;
  topK: number;
}

export interface CodeReference {
  filePath: string;
  chunkType: string;
  className?: string;
  methodName?: string;
  startLine: number;
  endLine: number;
  score: number;
  contentPreview?: string;
}

/** Agent 执行轨迹中的一步（思考 → 工具 → 观察）。 */
export interface AgentStep {
  stepIndex: number;
  thought?: string;
  toolName: string;
  toolArgs?: string;
  observation?: string;
  discoveredCount?: number;
}

export interface CodeAnswer {
  repoId: number;
  sessionId?: number;
  question: string;
  answer: string;
  degraded?: boolean;
  degradeReason?: string;
  references: CodeReference[];
  modelName?: string;
  promptTokens?: number;
  completionTokens?: number;
  costMs?: number;
  /** 是否走 agentic 多步检索。 */
  agentMode?: boolean;
  /** agent 执行轨迹，供可视化展示。 */
  agentSteps?: AgentStep[];
  agentIterations?: number;
  agentToolCalls?: number;
}
