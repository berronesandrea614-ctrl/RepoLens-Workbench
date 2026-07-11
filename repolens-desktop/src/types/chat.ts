export interface MentionChip {
  type: 'file' | 'symbol' | 'selection';
  label: string;   // display label
  value: string;   // file path or symbol ref
  extra?: string;  // selection text for 'selection' type
}

export interface CodeAnswerPayload {
  question: string;
  topK: number;
  /** "ask"（默认，只读问答）| "code"（允许 agent 直接改文件）。省略时后端按 ask 处理。 */
  mode?: "ask" | "code";
  /** 续接已有会话；省略时后端新建会话。用于多轮对话的短期记忆。 */
  sessionId?: number;
  /** @提及的上下文列表，最多5条 */
  mentions?: Array<{ type: string; value: string; extra?: string }>;
  /**
   * code 模式下开启「实时改动高亮」：agent 每改一个文件即经 SSE 推 file_change 事件，
   * 前端在 Monaco 里 inline diff 高亮（绿增红删）。用户在 UI 勾「实时」时传 true。
   */
  realtimeDiff?: boolean;
  /**
   * 权限模式（code 模式生效，对接内核 M4 五档 PermissionMode）：
   * DEFAULT | PLAN（只读规划）| ACCEPT_EDITS（自动接受编辑）| AUTO（自动执行）| BYPASS（绕过全部确认）。
   */
  permissionMode?: "DEFAULT" | "PLAN" | "ACCEPT_EDITS" | "AUTO" | "BYPASS";
}

/**
 * 实时改动事件（agent 开 realtimeDiff 后经 /chat/answer/stream 的 file_change 事件推送）。
 * 同一 filePath 多次事件以最新覆盖。
 */
export interface FileChange {
  stepIndex: number;
  /** 本 run 的会话 id（前端逐处 accept/reject 时定位活动影子区）。 */
  sessionId: number;
  filePath: string;
  changeType: "CREATE" | "WRITE";
  /** 真目录基线全文（新建时为空串）。 */
  before: string;
  /** 影子区当前全文。 */
  after: string;
}

/** 一次编码模式回答产生的文件改动摘要（详情经 changeApi 拉取）。 */
export interface FileChangeSummary {
  id: number;
  filePath: string;
  changeId: number;
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
  agentMode?: boolean;
  agentSteps?: AgentStep[];
  agentIterations?: number;
  agentToolCalls?: number;
  /** 编码模式下本次回答修改的文件（仅在 mode:"code" 且确有改动时出现）。 */
  fileChanges?: FileChangeSummary[];
  /** 本次回答对应的 Agent 执行记录 id（可跳转到执行轨迹视图）。 */
  agentRunId?: number;
}

/** 一个候选选项（对标 Claude 的多选卡片）。 */
export interface AskOption {
  label: string;
  description?: string;
}

/** 一个问题：正文 + 短标题（切换标签）+ 候选选项（空则自由文本作答）+ 是否多选。 */
export interface AskSubQuestion {
  header?: string;
  question: string;
  multiSelect?: boolean;
  options?: AskOption[];
}

/**
 * askUser 反问事件（agent 需要用户拍板方案/补关键需求时经 SSE 的 ask 事件推送）。
 * 前端据此渲染多选卡片，用户作答后合成回复经 POST /agent/answer 带 questionId 回传，唤醒挂起的 agent。
 */
export interface AskQuestion {
  questionId: string;
  questions: AskSubQuestion[];
  summary?: string;
}

/** SSE 流式回答的事件回调集合。 */
export interface StreamHandlers {
  /** meta 事件：初始引用 + 模型名，供尽早渲染。 */
  onMeta?: (meta: { references: CodeReference[]; modelName?: string }) => void;
  /** token 事件：一段文本增量，追加到已显示答案。 */
  onToken?: (text: string) => void;
  /**
   * step 事件（单数）：agent 每步工具调用完成后立即推送，用于增量渲染轨迹。
   * 流式 agent 路径专用；非流式回落时不触发此事件，仅触发 steps（复数）。
   */
  onStep?: (step: AgentStep) => void;
  /** steps 事件（复数）：非流式 agent 完整轨迹一次性推送，或流式路径回落时使用。 */
  onSteps?: (steps: {
    agentSteps?: AgentStep[];
    agentIterations?: number;
    agentToolCalls?: number;
  }) => void;
  /** done 事件：完整 CodeAnswer，作为权威结果覆盖流式拼接。 */
  onDone?: (answer: CodeAnswer) => void;
  /** error 事件或流失败。 */
  onError?: (message: string) => void;
  /** file_change 事件：实时改动高亮（agent 开 realtimeDiff 后逐文件推送）。 */
  onFileChange?: (change: FileChange) => void;
  /** ask 事件：agent 反问用户，需弹提问卡等待回复。 */
  onAsk?: (ask: AskQuestion) => void;
}
