import { useState } from "react";
import { SolutionSetView, SolutionBranchView, selectSolutionBranch } from "../../api/solutionApi";
import "./solution.css";

/**
 * M8 方案卡组：把一次 fanout 的 N 个并行方案并排展示，指标来自真实 staged 改动。
 * 每张卡可「选用」——只有选定的分支才合并回真目录，其余作废；隔离让对比阶段零副作用。
 *
 * @param onSelected 选定成功后回传更新过的视图，由 ChatPanel 写回对应消息（选中卡定型、其余标作废）。
 */
export function SolutionCards({ repoId, set, onSelected }: {
  repoId: number;
  set: SolutionSetView;
  onSelected: (updated: SolutionSetView) => void;
}) {
  // 正在合并的分支 id（null=空闲）；合并期间禁用全部按钮，避免并发选用。
  const [selecting, setSelecting] = useState<number | null>(null);
  const [error, setError] = useState<string>();
  const decided = set.selectedBranchId != null;

  async function choose(branchId: number) {
    if (decided || selecting != null) return;
    setSelecting(branchId);
    setError(undefined);
    try {
      onSelected(await selectSolutionBranch(repoId, set.setId, branchId));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSelecting(null);
    }
  }

  // 推荐分支排最前，其余按生成序（variantIndex）稳定排列。
  const branches = [...set.branches].sort(
    (a, b) => Number(b.recommended) - Number(a.recommended) || a.variantIndex - b.variantIndex,
  );

  return (
    <div className="sol-cards">
      <div className="sol-head">
        <span className="sol-title">{decided ? "多方案 · 已选定" : "多方案并行探索"}</span>
        <span className="sol-sub">
          {set.branches.length} 个方案各自独占影子区隔离并行，指标取自真实改动；
          {decided ? "已选定分支合并回真目录，其余作废" : "选定其一才合并回真目录，其余作废"}
        </span>
      </div>
      {error && <div className="sol-error">{error}</div>}
      <div className="sol-grid">
        {branches.map((b) => (
          <SolutionCard
            key={b.branchId}
            branch={b}
            decided={decided}
            selected={set.selectedBranchId === b.branchId}
            selecting={selecting === b.branchId}
            disabled={selecting != null}
            onChoose={() => void choose(b.branchId)}
          />
        ))}
      </div>
    </div>
  );
}

function SolutionCard({ branch, decided, selected, selecting, disabled, onChoose }: {
  branch: SolutionBranchView;
  decided: boolean;
  selected: boolean;
  selecting: boolean;
  disabled: boolean;
  onChoose: () => void;
}) {
  const discarded = decided && !selected;
  const verify =
    branch.verified == null
      ? { cls: "unknown", text: "未验证" }
      : branch.verified
        ? { cls: "ok", text: "验证通过" }
        : { cls: "fail", text: "验证未过" };

  const cls =
    "sol-card" +
    (branch.recommended ? " sol-card--rec" : "") +
    (selected ? " sol-card--selected" : "") +
    (discarded ? " sol-card--discarded" : "");

  return (
    <div className={cls}>
      <div className="sol-card-head">
        <span className="sol-card-label">{branch.label}</span>
        {branch.recommended && (
          <span className="sol-badge sol-badge--rec" title="引擎按改动面 / churn / token 归一化打分推荐">
            ⭐ 推荐
          </span>
        )}
        {selected && <span className="sol-badge sol-badge--sel">✓ 已选用</span>}
        {discarded && <span className="sol-badge sol-badge--dis">已作废</span>}
      </div>

      {branch.strategyHint && <div className="sol-card-hint">{branch.strategyHint}</div>}

      <div className="sol-metrics">
        <Metric label="文件" value={branch.filesChanged} />
        <Metric label="+行" value={branch.linesAdded} tone="add" />
        <Metric label="−行" value={branch.linesRemoved} tone="del" />
        <Metric label="轮次" value={branch.turns} />
        <Metric label="token" value={branch.tokensSpent} />
      </div>

      <div className={`sol-verify sol-verify--${verify.cls}`} title="未物理跑测试则保持未验证——不谎报绿">
        <span className="sol-verify-dot" />
        {verify.text}
      </div>

      {branch.finalText && <div className="sol-final">{branch.finalText}</div>}

      {!decided && (
        <button className="sol-choose" disabled={disabled} onClick={onChoose}>
          {selecting ? "合并中…" : "选用此方案"}
        </button>
      )}
    </div>
  );
}

function Metric({ label, value, tone }: { label: string; value: number; tone?: "add" | "del" }) {
  return (
    <span className="sol-metric">
      <span className="sol-metric-label">{label}</span>
      <span className={`sol-metric-value${tone ? ` sol-metric-value--${tone}` : ""}`}>{value}</span>
    </span>
  );
}
