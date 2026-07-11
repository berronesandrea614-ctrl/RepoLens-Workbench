import { http } from "./http";
import {
  ComprehensionDebtDashboard,
  FileExplanation,
  MarkReviewedRequest,
  QuizQuestion,
  QuizResult,
  RepayPath,
} from "../types/debt";

/** 获取理解债务仪表盘（Top 列表 + 信号明细）。 */
export async function fetchDebtDashboard(
  repoId: number,
  minScore = 40,
): Promise<ComprehensionDebtDashboard> {
  return (await http.get(`/api/repos/${repoId}/comprehension-debt`, {
    params: { minScore },
  })) as unknown as ComprehensionDebtDashboard;
}

/** 获取单个文件的偿债路径（理由卡片 + 长期记忆 + Claude 占位）。 */
export async function fetchRepayPath(
  repoId: number,
  fileId: number,
): Promise<RepayPath> {
  return (await http.get(
    `/api/repos/${repoId}/comprehension-debt/${fileId}/repay`,
  )) as unknown as RepayPath;
}

/** 复核埋点：更新 file_change_log 复核字段，喂给 S2 信号。 */
export async function markReviewed(
  repoId: number,
  changeId: number,
  request: MarkReviewedRequest,
): Promise<void> {
  await http.post(
    `/api/repos/${repoId}/changes/${changeId}/mark-reviewed`,
    request,
  );
}

/**
 * 触发仓库债务全量重算（同步，等待完成后返回）。
 * 用于「重新计算债务」按钮。
 */
export async function recomputeDebt(repoId: number): Promise<void> {
  await http.post(`/api/repos/${repoId}/comprehension-debt/recompute`);
}

/**
 * 生成文件讲解：让 AI 讲清该文件在项目中的作用/职责 + 业务前因后果，
 * 并给出直接相关的文件/符号清单（含相对路径，供点击跳转）。后端按 {repoId,fileId} 缓存。
 */
export async function explainFile(
  repoId: number,
  fileId: number,
): Promise<FileExplanation> {
  return (await http.get(
    `/api/repos/${repoId}/comprehension-debt/${fileId}/explain`,
  )) as unknown as FileExplanation;
}

/** 为指定文件生成理解测验题（3 道多选一，LLM 出题，失败降级静态题）。 */
export async function generateQuiz(
  repoId: number,
  fileId: number,
): Promise<QuizQuestion[]> {
  return (await http.get(
    `/api/repos/${repoId}/comprehension-debt/${fileId}/quiz`,
  )) as unknown as QuizQuestion[];
}

/** 提交测验作答并评分。answers[i] 为第 i 题选中的 0-based 选项序号。 */
export async function submitQuiz(
  repoId: number,
  fileId: number,
  answers: number[],
): Promise<QuizResult> {
  return (await http.post(
    `/api/repos/${repoId}/comprehension-debt/${fileId}/quiz/submit`,
    { answers },
  )) as unknown as QuizResult;
}
