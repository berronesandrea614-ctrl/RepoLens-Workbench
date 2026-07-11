import { http } from "./http";

/**
 * M8 方案分支多方案对比（对接 bridge KernelSolutionController `/api/repos/{repoId}/solution-sets/*`）。
 *
 * fanout 把一个「有多种合理实现」的任务并行分给 N 个 agent，各喂不同策略、各自独占一个影子区隔离运行、
 * 都不落真目录，跑完拿真实 staged 改动统计出指标并打分推荐；select 选定一个分支后才把它合并回真目录、
 * 其余作废。真正的写只发生在选定之后——隔离让多方案并行零副作用。
 */

/** 单个方案分支的对比明细，指标全部来自真实 staged 改动。 */
export interface SolutionBranchView {
  branchId: number;
  label: string;
  strategyHint: string;
  variantIndex: number;
  /** 指标来源；REAL=来自真实 staged 改动统计（非估算）。 */
  metricKind: string;
  status: string;
  filesChanged: number;
  linesAdded: number;
  linesRemoved: number;
  tokensSpent: number;
  turns: number;
  /** 是否物理验证通过；null=未跑测试（不谎报绿，对齐 failing-until-tested）。 */
  verified: boolean | null;
  terminationReason: string;
  finalText: string;
  /** 引擎按改动面/churn/token 打分推荐（⭐，建议非强制，最终用户选）。 */
  recommended: boolean;
}

/** 一个方案组的完整视图：状态 + 选中/推荐 + 各分支明细。 */
export interface SolutionSetView {
  setId: number;
  repoId: number;
  sessionId: number;
  engine: string;
  status: string;
  selectedBranchId: number | null;
  recommendedBranchId: number | null;
  branches: SolutionBranchView[];
}

/** 一个可选的策略提示（省略则后端用默认三方案：最小改动/清晰重构/稳健防御）。 */
export interface StrategyDTO {
  label: string;
  hint: string;
}

/**
 * 触发多方案 fanout：同步阻塞，后端并行跑 N 个 agent，耗时随分支数与模型而定（分钟级）。
 * 故这里把超时放到 10 分钟，覆盖真实模型跑多分支的墙钟。
 */
export async function fanoutSolutions(
  repoId: number,
  question: string,
  sessionId?: number,
  strategies?: StrategyDTO[],
): Promise<SolutionSetView> {
  return (await http.post(
    `/api/repos/${repoId}/solution-sets/fanout`,
    { question, sessionId, strategies },
    { timeout: 600000 },
  )) as unknown as SolutionSetView;
}

/** 拉某方案组的最新视图（聊天卡组与对比窗共读同一后端映像）。 */
export async function getSolutionSet(repoId: number, setId: number): Promise<SolutionSetView> {
  return (await http.get(`/api/repos/${repoId}/solution-sets/${setId}`)) as unknown as SolutionSetView;
}

/** 选定一个分支：只把它合并回真目录，其余分支 DISCARDED（改动作废、影子区丢弃）。 */
export async function selectSolutionBranch(
  repoId: number,
  setId: number,
  branchId: number,
): Promise<SolutionSetView> {
  return (await http.post(
    `/api/repos/${repoId}/solution-sets/${setId}/select`,
    { branchId },
  )) as unknown as SolutionSetView;
}
