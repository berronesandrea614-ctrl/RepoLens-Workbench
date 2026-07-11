export interface GraphNode {
  id: string;
  label: string;
  className?: string;
  methodName?: string;
  symbolType: string;
  filePath?: string;
  startLine?: number;
  endLine?: number;
  resolved: boolean;
  signature?: string;
  summary?: string;
  layer?: string;
  /** Feature A: 理解债务分 0–100，null=无债务数据。 */
  debtScore?: number | null;
  /** Feature A: 债务分档颜色（来自后端 GraphNodeVO.debtColor）。 */
  debtColor?: string | null;
  /** Feature J: 时间轴帧中节点变更类型。 */
  changeType?: "NEW" | "MODIFIED" | "STABLE";
  /** Feature J: 该节点首次出现的帧序号。 */
  firstSeenFrame?: number;
  /** Feature J: 累计被触碰的帧次数。 */
  touchCount?: number;
}

export interface GraphEdge {
  id: string;
  source: string;
  target: string;
  relationType: string;
  confidence: number;
  dataType?: string;
}

export interface CodeGraph {
  rootId: string;
  nodes: GraphNode[];
  edges: GraphEdge[];
  nodeCount: number;
  edgeCount: number;
  truncated: boolean;
}

export interface SymbolHit {
  id: number;
  className?: string;
  methodName?: string;
  symbolType: string;
  startLine?: number;
  endLine?: number;
}
