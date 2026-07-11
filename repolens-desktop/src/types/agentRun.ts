/** Agent 执行记录的元数据（列表项 / trace.run）。对应后端 AgentRunVO。 */
export interface AgentRunMeta {
  id: number;
  sessionId?: number;
  question: string;
  /** "ask" 问答 | "code" 编码（后端裸传 mode 字符串）。 */
  mode: string;
  iterations: number;
  toolCalls: number;
  status: string;
  createdAt: string;
  stepCount: number;
}

/** 一步执行轨迹。对应后端 AgentRunStepVO。 */
export interface AgentRunStep {
  id: number;
  stepIndex: number;
  type: "THINK" | "TOOL" | "WRITE";
  toolName?: string;
  toolArgs?: string;
  thought?: string;
  observationSummary?: string;
  /** 该步触及的文件（WRITE / 部分 TOOL 步会带上）。 */
  targetFiles?: string[];
  status: string;
}

/** 单次执行的完整轨迹。对应 GET /agent-runs/{runId}/trace。 */
export interface AgentRunTrace {
  run: AgentRunMeta;
  steps: AgentRunStep[];
}

/** 单个改动文件的摘要。 */
export interface ChangedFileVO {
  filePath: string;
  changeStatus: string;
  changeLogId: number;
}

/** 上/下游子图（节点 + 边）。 */
export interface BlastSubgraph {
  nodes: BlastGraphNode[];
  edges: BlastGraphEdge[];
}

/** 影响面图中的节点（对应后端 GraphNodeVO）。 */
export interface BlastGraphNode {
  id: string;
  label: string;
  filePath?: string;
  changeType?: string;    // "MODIFIED" for changed symbols
  layer?: string;
  symbolType?: string;
  startLine?: number;
  endLine?: number;
  resolved?: boolean;
  signature?: string;
  summary?: string;
}

/** 影响面图中的边（对应后端 GraphEdgeVO）。 */
export interface BlastGraphEdge {
  id: string;
  source: string;
  target: string;
  relationType?: string;
  confidence?: number;
}

/** change-graph 端点的完整响应。 */
export interface ChangeGraph {
  changedFiles: ChangedFileVO[];
  changedSymbols: BlastGraphNode[];
  upstream: BlastSubgraph;
  downstream: BlastSubgraph;
  truncated: boolean;
}
