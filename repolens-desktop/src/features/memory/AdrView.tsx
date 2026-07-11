import { useCallback, useEffect, useState } from "react";
import {
  acceptAdr,
  formatAdrNumber,
  generateAdr,
  listAdrs,
  statusClass,
  statusLabel,
  supersedeAdr,
} from "../../api/adrApi";
import type { Adr } from "../../api/adrApi";
import { useWorkbench } from "../../state/workbenchStore";
import "./memory.css";

/**
 * Feature I MVP: 自动 ADR 面板。
 *
 * 布局：左列 ADR 列表 + 右列 MADR 分节详情。
 * 工具栏：需求 ID 输入 + "结晶为 ADR" 按钮；选中 PROPOSED 时显示"采纳"按钮。
 * 采纳后 filePath 可点击调用 openFile。
 */
export function AdrView() {
  const repoId = useWorkbench((s) => s.repoId);
  const openFile = useWorkbench((s) => s.openFile);

  const [adrs, setAdrs] = useState<Adr[]>([]);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [loading, setLoading] = useState(false);
  const [generating, setGenerating] = useState(false);
  const [accepting, setAccepting] = useState(false);
  const [superseding, setSuperseding] = useState(false);
  const [supersedingId, setSupersedingId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [reqIdInput, setReqIdInput] = useState("");

  const selected = adrs.find((a) => a.id === selectedId) ?? null;

  const load = useCallback(async () => {
    if (!repoId) return;
    setLoading(true);
    setError(null);
    try {
      const data = await listAdrs(repoId);
      setAdrs(data);
    } catch (err: unknown) {
      setError(String(err));
    } finally {
      setLoading(false);
    }
  }, [repoId]);

  useEffect(() => {
    load();
  }, [load]);

  const handleGenerate = useCallback(async () => {
    if (!repoId || generating) return;
    const reqId = parseInt(reqIdInput, 10);
    if (!Number.isFinite(reqId) || reqId <= 0) {
      setError("请输入有效的需求 ID（正整数）");
      return;
    }
    setGenerating(true);
    setError(null);
    try {
      const newAdr = await generateAdr(repoId, reqId);
      setAdrs((prev) => {
        // 避免重复
        const existing = prev.findIndex((a) => a.id === newAdr.id);
        if (existing >= 0) {
          const updated = [...prev];
          updated[existing] = newAdr;
          return updated;
        }
        return [newAdr, ...prev];
      });
      setSelectedId(newAdr.id);
    } catch (err: unknown) {
      setError(String(err));
    } finally {
      setGenerating(false);
    }
  }, [repoId, generating, reqIdInput]);

  const handleAccept = useCallback(async () => {
    if (!repoId || !selected || accepting) return;
    setAccepting(true);
    setError(null);
    try {
      const updated = await acceptAdr(repoId, selected.id);
      setAdrs((prev) => prev.map((a) => (a.id === updated.id ? updated : a)));
      setSelectedId(updated.id);
    } catch (err: unknown) {
      setError(String(err));
    } finally {
      setAccepting(false);
    }
  }, [repoId, selected, accepting]);

  // 切换选中 ADR 时重置取代选择器。
  useEffect(() => {
    setSupersedingId(null);
  }, [selectedId]);

  const handleSupersede = useCallback(async () => {
    if (!repoId || !selected || !supersedingId || superseding) return;
    setSuperseding(true);
    setError(null);
    try {
      const updated = await supersedeAdr(repoId, selected.id, supersedingId);
      setAdrs((prev) => prev.map((a) => (a.id === updated.id ? updated : a)));
      setSelectedId(updated.id);
      setSupersedingId(null);
    } catch (err: unknown) {
      setError(String(err));
    } finally {
      setSuperseding(false);
    }
  }, [repoId, selected, supersedingId, superseding]);

  if (!repoId) {
    return (
      <div className="adr-empty">
        <span className="codicon codicon-notebook" />
        <p>请先打开一个仓库</p>
      </div>
    );
  }

  if (loading && adrs.length === 0) {
    return <div className="adr-loading">加载 ADR 列表…</div>;
  }

  return (
    <div className="adr-view">
      {/* ── 工具栏 ── */}
      <div className="adr-toolbar">
        <span className="adr-toolbar-label">需求 ID</span>
        <input
          className="adr-toolbar-input"
          type="number"
          min={1}
          placeholder="如 42"
          value={reqIdInput}
          onChange={(e) => setReqIdInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") handleGenerate();
          }}
        />
        <button
          className="adr-btn"
          onClick={handleGenerate}
          disabled={generating || !reqIdInput}
          title="从当前需求结晶生成 ADR"
        >
          {generating ? "生成中…" : "结晶为 ADR"}
        </button>
        <div className="adr-toolbar-spacer" />
        {selected && selected.status === "PROPOSED" && (
          <button
            className="adr-btn adr-btn--accept"
            onClick={handleAccept}
            disabled={accepting}
            title="采纳该 ADR，分配编号并写入 MADR 文件"
          >
            {accepting ? "采纳中…" : "采纳"}
          </button>
        )}
        <button className="adr-btn adr-btn--secondary" onClick={load} title="刷新列表">
          <span className="codicon codicon-refresh" />
        </button>
      </div>

      {/* ── 错误横幅 ── */}
      {error && <div className="adr-error-banner">{error}</div>}

      {/* ── 主体：左列列表 + 右列详情 ── */}
      <div className="adr-body">
        {/* 左列：ADR 列表 */}
        <div className="adr-list-pane">
          <div className="adr-list-header">ADR ({adrs.length})</div>
          {adrs.length === 0 && (
            <div className="adr-list-empty">暂无 ADR，输入需求 ID 后点击「结晶为 ADR」</div>
          )}
          {adrs.map((adr) => (
            <div
              key={adr.id}
              className={`adr-list-item${selectedId === adr.id ? " adr-list-item--active" : ""}`}
              onClick={() => setSelectedId(adr.id)}
            >
              <span className="adr-list-number">{formatAdrNumber(adr.number)}</span>
              <span className="adr-list-title" title={adr.title}>
                {adr.title}
              </span>
              <span className={`adr-status-badge adr-status-badge--${statusClass(adr.status)}`}>
                {statusLabel(adr.status)}
              </span>
            </div>
          ))}
        </div>

        {/* 右列：MADR 详情 */}
        <div className="adr-detail-pane">
          {!selected ? (
            <div className="adr-detail-empty">
              <span className="codicon codicon-notebook" />
              <p>选择左侧 ADR 查看详情</p>
            </div>
          ) : (
            <>
              {/* 标题区 */}
              <div className="adr-detail-header">
                <span className="adr-detail-title">
                  ADR-{formatAdrNumber(selected.number)} · {selected.title}
                </span>
                <span
                  className={`adr-status-badge adr-status-badge--${statusClass(selected.status)}`}
                >
                  {statusLabel(selected.status)}
                </span>
                {selected.degraded && (
                  <span className="adr-degraded-chip" title="LLM 不可用，模板降级生成">
                    <span className="codicon codicon-warning" />
                    降级生成（LLM 不可用）
                  </span>
                )}
              </div>

              {/* 操作区 */}
              <div className="adr-detail-actions">
                {selected.status === "ACCEPTED" && selected.filePath && (
                  <div className="adr-detail-filepath">
                    <span className="codicon codicon-file" />
                    <span
                      className="adr-filepath-link"
                      title={`打开 ${selected.filePath}`}
                      onClick={() => openFile(selected.filePath!)}
                    >
                      {selected.filePath}
                    </span>
                  </div>
                )}

                {/* 标记被取代（仅 ACCEPTED 状态下显示） */}
                {selected.status === "ACCEPTED" && (
                  <div className="adr-supersede-row">
                    <span className="adr-toolbar-label">标记被取代</span>
                    <select
                      className="adr-supersede-select"
                      value={supersedingId ?? ""}
                      onChange={(e) =>
                        setSupersedingId(e.target.value ? Number(e.target.value) : null)
                      }
                    >
                      <option value="">选择取代的 ADR…</option>
                      {adrs
                        .filter(
                          (a) =>
                            a.id !== selected.id &&
                            (a.status === "ACCEPTED" || a.status === "PROPOSED"),
                        )
                        .map((a) => (
                          <option key={a.id} value={a.id}>
                            ADR-{formatAdrNumber(a.number)} {a.title}
                          </option>
                        ))}
                    </select>
                    <button
                      className="adr-btn"
                      onClick={handleSupersede}
                      disabled={!supersedingId || superseding}
                      title="将当前 ADR 标记为被所选 ADR 取代"
                    >
                      {superseding ? "标记中…" : "确认取代"}
                    </button>
                  </div>
                )}

                {/* 被取代徽章（SUPERSEDED 状态） */}
                {selected.status === "SUPERSEDED" && (
                  <div className="adr-superseded-note">
                    <span className="codicon codicon-arrow-right" />
                    {(() => {
                      const sup =
                        selected.supersededBy != null
                          ? adrs.find((a) => a.id === selected.supersededBy)
                          : null;
                      return sup
                        ? `被 ADR-${formatAdrNumber(sup.number)} 取代`
                        : "已被取代";
                    })()}
                  </div>
                )}
              </div>

              {/* MADR 分节 */}
              <MadrSection title="Context（背景）" content={selected.context} />

              <div className="adr-section">
                <div className="adr-section-title">Decision Drivers（决策驱动因素）</div>
                {selected.drivers && selected.drivers.length > 0 ? (
                  <div className="adr-tag-list">
                    {selected.drivers.map((d, i) => (
                      <span key={i} className="adr-tag">
                        {d}
                      </span>
                    ))}
                  </div>
                ) : (
                  <span className="adr-section-empty">无</span>
                )}
              </div>

              <div className="adr-section">
                <div className="adr-section-title">Considered Options（候选方案）</div>
                {selected.options && selected.options.length > 0 ? (
                  <div className="adr-tag-list">
                    {selected.options.map((o, i) => (
                      <span key={i} className="adr-tag">
                        {o}
                      </span>
                    ))}
                  </div>
                ) : (
                  <span className="adr-section-empty">无</span>
                )}
              </div>

              <MadrSection title="Decision Outcome（决策结论）" content={selected.decision} />

              <MadrSection title="Consequences（后果）" content={selected.consequences} />
            </>
          )}
        </div>
      </div>
    </div>
  );
}

function MadrSection({ title, content }: { title: string; content: string | null }) {
  return (
    <div className="adr-section">
      <div className="adr-section-title">{title}</div>
      {content ? (
        <div className="adr-section-content">{content}</div>
      ) : (
        <span className="adr-section-empty">无</span>
      )}
    </div>
  );
}
