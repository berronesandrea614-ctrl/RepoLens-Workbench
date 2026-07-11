/** TypeScript mirrors of backend ReconciliationVO (Feature B P1). */

export interface ReconciliationSummary {
  coverage: number;      // 0.0~1.0
  fidelity: number;      // 0.0~1.0
  offPlanCount: number;
  violationCount: number;
  trustFlag: string;     // OK | SUSPECT | FABRICATED
  humanLine?: string;
}

export interface PlanItemRecon {
  stepId: string;
  title: string;
  /** LANDED | PARTIAL | MISSING_ATTEMPTED | MISSING_SILENT */
  status: string;
  declaredFiles: string[];
  landedFiles: string[];
  missingFiles: string[];
  declaredOp?: string;   // CREATE | MODIFY | DELETE
}

export interface OffPlanChange {
  filePath: string;
  /** OVER_SCOPE | SILENT_ADD */
  classification: string;
  changeId?: number;
  opType?: string;
  /** 文件业务内容摘要（从改动内容解析）。 */
  summary?: string;
  /** 一句话签名，如 "class PersonalBlog { id, title, content... }"。 */
  sig?: string;
}

export interface SelfReportCheck {
  type: string;        // FABRICATED_VERIFICATION | CLAIM_CONTRADICTS_RESULT | TEST_WEAKENED | NO_OP_SUCCESS
  severity: string;    // RED | ORANGE
  detail: string;
}

export interface SelfReport {
  claimedSuccess: boolean;
  claimedVerified: boolean;
  claimEvidence?: string;
  trustFlag: string;   // OK | SUSPECT | FABRICATED
  staleVerification?: boolean;
  checks: SelfReportCheck[];
}

export interface ConstraintViolation {
  /** Rule type: PATH_FORBIDDEN | FILETYPE_FORBIDDEN | NO_NEW_DEP | MUST_VERIFY | KEEP_SCOPE | SEMANTIC */
  ruleType: string;
  rawText: string;
  matchedFiles: string[];
  /** BLOCK | WARN */
  severity: string;
}

export interface ReconciliationVO {
  planned: boolean;
  degrade: boolean;
  summary?: ReconciliationSummary;
  items: PlanItemRecon[];
  offPlan: OffPlanChange[];
  selfReport?: SelfReport;
  violations?: ConstraintViolation[];
}
