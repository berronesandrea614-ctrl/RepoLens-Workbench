/** TypeScript mirrors of backend RequirementInsightVO (read-only front-end types). */

export interface FlowNode {
  nodeType: 'node';
  role?: string;
  name?: string;
  sig?: string;
  note?: string;
  delta?: string;
  /** new | mod | danger | offp | ext | dim | '' */
  cls?: string;
  tag?: string;
  symbolId?: number;
  filePath?: string;
  startLine?: number;
  endLine?: number;
  changeId?: number;
  external?: boolean;
}

export interface FlowEdge {
  nodeType: 'edge';
  data?: string;
  mut?: boolean;
}

export type FlowItem = FlowNode | FlowEdge;

export interface InsightChips {
  filesChanged: number;
  added: number;
  modified: number;
  plannedStepsDone: number;
  plannedStepsTotal: number;
  offPlanCount: number;
}

export interface InsightDeviation {
  files: string[];
  note: string;
}

export interface InsightStep {
  index: number;
  title: string;
  why?: string;
  /** in | risk | off */
  kind: string;
  riskNote?: string;
  insight?: string;
  toolReads?: string[];
  flow: FlowItem[];
}

export interface InsightFooterData {
  plannedDone?: string;
  offPlanPending: number;
  impactNote?: string;
}

export interface PanoramaLayer {
  label: string;
  flow: FlowItem[];
}

export interface PanoramaData {
  layers: PanoramaLayer[];
}

export interface RequirementInsightVO {
  intent: string;
  approach?: string;
  planned: boolean;
  hasChanges: boolean;
  chips?: InsightChips;
  deviation?: InsightDeviation | null;
  steps: InsightStep[];
  footer?: InsightFooterData;
  panorama?: PanoramaData | null;
}
