/** 七信号明细（对应后端 SignalBreakdownVO）。 */
export interface SignalBreakdown {
  s1AiChangedLines: number;
  s1LineCount: number;
  s1Norm: number;

  s2ReviewLevel: number;
  s2Norm: number;

  s3HasRationale: boolean;
  s3Norm: number;

  s4MaxCognitive: number;
  s4MaxCyclomatic: number;
  s4Norm: number;

  s5Churn14dCount: number;
  s5Norm: number;

  s6Norm: number;
  s6Degraded: boolean;

  s7HasTestFile: boolean;
  s7Norm: number;

  ampFactor: number;
  base: number;
  score: number;
  band: "RED" | "YELLOW" | "GREEN";
}

/** 单个文件的债务单元（对应后端 DebtUnitVO）。 */
export interface DebtUnit {
  fileId: number;
  filePath: string;
  score: number;
  band: "RED" | "YELLOW" | "GREEN";
  lineCount: number;
  signals: SignalBreakdown;
  degraded: boolean;
}

/** 仪表盘主 VO（对应后端 ComprehensionDebtVO）。 */
export interface ComprehensionDebtDashboard {
  repoId: number;
  redCount: number;
  yellowCount: number;
  greenCount: number;
  topDebt: DebtUnit[];
  stale: boolean;
  degraded: boolean;
}

/** 偿债路径（对应后端 RepayPathVO）。 */
export interface RepayPath {
  fileId: number;
  filePath: string;
  rationales: string[];
  memories: string[];
  canAskClaude: boolean;
  suggestedPrompt: string;
  currentScore: number;
  currentBand: "RED" | "YELLOW" | "GREEN";
}

/** mark-reviewed 请求体。 */
export interface MarkReviewedRequest {
  reviewType: "DIFF_VIEWED" | "ACCEPTED" | "QUIZZED";
  dwellMs?: number;
  quizScore?: number;
}

/** 相关文件项（对应后端 FileExplanationVO.RelatedFile）。 */
export interface RelatedFile {
  path: string;
  reason: string;
}

/** 相关符号项（对应后端 FileExplanationVO.RelatedSymbol）。 */
export interface RelatedSymbol {
  name: string;
  path?: string | null;
  reason: string;
}

/** 文件讲解（对应后端 FileExplanationVO，替代考试式出题）。 */
export interface FileExplanation {
  fileId: number;
  filePath: string;
  explanation: string;
  relatedFiles: RelatedFile[];
  relatedSymbols: RelatedSymbol[];
  degraded: boolean;
}

/** 单道理解测验题（正确答案在服务端，不包含在这里）。 */
export interface QuizQuestion {
  id: number;
  questionText: string;
  choices: string[];
}

/** 测验提交结果。 */
export interface QuizResult {
  quizScore: number;
  passed: boolean;
  feedbacks: string[];
  changeId?: number | null;
  newDebtScore?: number | null;
  newDebtBand?: "RED" | "YELLOW" | "GREEN" | null;
}

/** 债务等级对应颜色。 */
export const BAND_COLORS: Record<string, string> = {
  RED:    "#e74c3c",
  YELLOW: "#f39c12",
  GREEN:  "#27ae60",
};

/** 信号名称国际化映射。 */
export const SIGNAL_LABELS: Record<string, string> = {
  S1: "AI 改动占比",
  S2: "复核程度",
  S3: "有无理由",
  S4: "认知复杂度",
  S5: "近期 Churn",
  S6: "人类改动间隔",
  S7: "测试覆盖",
};
