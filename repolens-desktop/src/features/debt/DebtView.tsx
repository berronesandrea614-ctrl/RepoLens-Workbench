import { useCallback, useEffect, useState } from "react";
import { fetchDebtDashboard, recomputeDebt } from "../../api/debtApi";
import { ComprehensionDebtDashboard, DebtUnit } from "../../types/debt";
import { useWorkbench } from "../../state/workbenchStore";
import { DebtTopList } from "./DebtTopList";
import { DebtDrilldown } from "./DebtDrilldown";
import "./debt.css";

/**
 * 理解债务仪表盘主视图（仿 GraphView 布局）。
 *
 * 布局：左侧 Top 列表 | 右侧 下钻面板（信号雷达 + 偿债路径）。
 * 调用图热力染色：通过 GraphNodeVO.debtScore/debtColor 在 GraphCanvas 中按 debtColor 分支
 * 着色（后端已注入），用户点击 ActivityBar 的「数据流转」即可看到热力图。
 * MVP 本视图提供 Top 列表 + 下钻详情，不重复渲染调用图（复用已有 GraphView）。
 */
export function DebtView() {
  const repoId = useWorkbench((s) => s.repoId);
  const [dashboard, setDashboard] = useState<ComprehensionDebtDashboard | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<DebtUnit | null>(null);
  const [recomputing, setRecomputing] = useState(false);

  const load = useCallback(async () => {
    if (!repoId) return;
    setLoading(true);
    setError(null);
    try {
      const data = await fetchDebtDashboard(repoId, 40);
      setDashboard(data);
      if (data.topDebt.length > 0 && !selected) {
        setSelected(data.topDebt[0]);
      }
    } catch (err: unknown) {
      setError(String(err));
    } finally {
      setLoading(false);
    }
  }, [repoId]); // eslint-disable-line react-hooks/exhaustive-deps

  const handleRecompute = useCallback(async () => {
    if (!repoId || recomputing) return;
    setRecomputing(true);
    try {
      await recomputeDebt(repoId);
      await load();
    } catch (err: unknown) {
      console.warn("recomputeDebt failed", err);
    } finally {
      setRecomputing(false);
    }
  }, [repoId, recomputing, load]);

  useEffect(() => {
    load();
  }, [load]);

  if (!repoId) {
    return (
      <div className="debt-empty">
        <span className="codicon codicon-warning" />
        <span>请先打开仓库</span>
      </div>
    );
  }

  if (loading && !dashboard) {
    return <div className="debt-loading">加载理解债务数据…</div>;
  }

  if (error) {
    return (
      <div className="debt-empty">
        <span className="codicon codicon-error" />
        <span>{error}</span>
        <button onClick={load} style={{ marginTop: 8, padding: "4px 12px", cursor: "pointer" }}>
          重试
        </button>
      </div>
    );
  }

  if (!dashboard) return null;

  return (
    <div className="debt-view">
      {/* 左：Top 列表 */}
      <div className="debt-sidebar">
        <div className="debt-sidebar-header">
          <h3>理解债务 Top</h3>
          <div className="debt-count-row">
            <span className="debt-count-badge red">{dashboard.redCount} 高危</span>
            <span className="debt-count-badge yellow">{dashboard.yellowCount} 预警</span>
            <span className="debt-count-badge green">{dashboard.greenCount} 健康</span>
          </div>
        </div>
        <DebtTopList
          items={dashboard.topDebt}
          activeFileId={selected?.fileId ?? null}
          onSelect={(unit) => setSelected(unit)}
        />
      </div>

      {/* 右：内容区 */}
      <div className="debt-main">
        <div className="debt-toolbar">
          <span className="debt-toolbar-title">理解债务仪表盘</span>
          {dashboard.stale && (
            <span className="debt-stale-badge">数据已刷新</span>
          )}
          {dashboard.degraded && (
            <span className="debt-degraded-badge">S6 降级（无 git）</span>
          )}
          <button
            onClick={handleRecompute}
            disabled={recomputing || loading}
            title="重新计算仓库内所有 AI 触碰过文件的债务分（同步，可能需要几秒）"
            style={{
              padding: "3px 10px",
              background: recomputing ? "#161b22" : "#238636",
              border: "1px solid #238636",
              borderRadius: 4,
              color: "#fff",
              cursor: recomputing ? "not-allowed" : "pointer",
              fontSize: 12,
            }}
          >
            {recomputing ? "计算中…" : "重新计算债务"}
          </button>
          <button
            onClick={load}
            disabled={loading}
            style={{
              padding: "3px 10px",
              background: "transparent",
              border: "1px solid #30363d",
              borderRadius: 4,
              color: "#e6edf3",
              cursor: "pointer",
              fontSize: 12,
            }}
          >
            {loading ? "刷新中…" : "⟳ 刷新"}
          </button>
        </div>

        <div className="debt-content">
          {selected ? (
            <DebtDrilldown unit={selected} onDebtUpdated={load} />
          ) : (
            <div className="debt-empty" style={{ height: "auto", paddingTop: 40 }}>
              <span className="codicon codicon-warning" />
              {dashboard.topDebt.length === 0 ? (
                <>
                  <span>暂无债务数据</span>
                  <span style={{ fontSize: 12, color: "var(--vs-fg-dim)", maxWidth: 440, lineHeight: 1.7, marginTop: 6 }}>
                    可能原因：① 所有文件债务分都低于 40（健康）；② 仓库<b style={{ color: "#e3b341" }}>尚未索引</b>——点右上「重新计算债务」；
                    ③ 这是<b style={{ color: "#e3b341" }}>非 Java 项目</b>——调用图/依赖已支持 TS/JS/Python/Go/Rust/C#/Ruby，
                    但债务评分依赖的复杂度信号（圈复杂度/认知复杂度）目前仅 Java 计算。
                  </span>
                </>
              ) : (
                <span>选择左侧文件查看详情</span>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
