import { useCallback, useEffect, useState } from "react";
import {
  fetchProvenanceRecords,
  verifyProvenance,
  getExportUrl,
  formatDecision,
  getDecisionClass,
  formatPromptFingerprint,
  formatDecidedAt,
  type ProvenanceRecord,
  type ProvenancePage,
  type ProvenanceVerifyResult,
} from "../../api/provenanceApi";
import { useWorkbench } from "../../state/workbenchStore";
import "./provenance.css";

/**
 * AI 贡献溯源审计视图（Feature F）。
 *
 * 布局：顶部工具栏（验证账本 + 导出）+ 分页时间线表格。
 * 列：时间 | 文件 | 操作 | 模型 | 批准人 | 决策 | prompt 指纹
 * P1: 点击行展开（AI 侧 + 人工侧 + hash）。
 * 符合 EU AI Act Art.12/Art.14/Art.19 审计要求。
 */
export function ProvenanceView() {
  const repoId = useWorkbench((s) => s.repoId);

  const [page, setPage] = useState<ProvenancePage | null>(null);
  const [currentPage, setCurrentPage] = useState(0);
  const [pageSize] = useState(20);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [verifyResult, setVerifyResult] = useState<ProvenanceVerifyResult | null>(null);
  const [verifying, setVerifying] = useState(false);

  const [expandedId, setExpandedId] = useState<number | null>(null);

  const load = useCallback(async () => {
    if (!repoId) return;
    setLoading(true);
    setError(null);
    try {
      const data = await fetchProvenanceRecords(repoId, currentPage, pageSize);
      setPage(data);
    } catch (err: unknown) {
      setError(String(err));
    } finally {
      setLoading(false);
    }
  }, [repoId, currentPage, pageSize]);

  const handleVerify = useCallback(async () => {
    if (!repoId || verifying) return;
    setVerifying(true);
    setVerifyResult(null);
    try {
      const result = await verifyProvenance(repoId);
      setVerifyResult(result);
    } catch (err: unknown) {
      setError(String(err));
    } finally {
      setVerifying(false);
    }
  }, [repoId, verifying]);

  useEffect(() => {
    load();
  }, [load]);

  if (!repoId) {
    return (
      <div className="provenance-empty">
        <span className="codicon codicon-verified" />
        <p>请先打开一个仓库</p>
      </div>
    );
  }

  const total = page?.total ?? 0;
  const totalPages = Math.max(1, Math.ceil(total / pageSize));

  return (
    <div className="provenance-view">
      {/* ── 顶部工具栏 ── */}
      <div className="provenance-toolbar">
        <span className="provenance-title">
          <span className="codicon codicon-verified" />
          AI 贡献溯源审计账本
        </span>
        <div className="provenance-toolbar-actions">
          <button
            className="prov-btn"
            onClick={handleVerify}
            disabled={verifying}
            title="验证账本哈希链完整性（检测篡改）"
          >
            {verifying ? "校验中…" : "验证账本完整性"}
          </button>
          <div className="prov-export-group">
            <span className="prov-export-label">导出</span>
            <a
              className="prov-btn prov-btn--outline"
              href={getExportUrl(repoId, "json")}
              download
              title="导出 JSON（含 EU AI Act 条款注释）"
            >
              JSON
            </a>
            <a
              className="prov-btn prov-btn--outline"
              href={getExportUrl(repoId, "csv")}
              download
              title="导出 CSV"
            >
              CSV
            </a>
            <a
              className="prov-btn prov-btn--outline"
              href={getExportUrl(repoId, "aibom")}
              download
              title="导出 AI-BOM（CycloneDX ML-BOM 1.5）"
            >
              AI-BOM
            </a>
          </div>
        </div>
      </div>

      {/* ── 验证结果横幅 ── */}
      {verifyResult && (
        <div
          className={`prov-verify-banner ${verifyResult.verified ? "prov-verify-ok" : "prov-verify-fail"}`}
        >
          <span className={`codicon ${verifyResult.verified ? "codicon-pass-filled" : "codicon-error"}`} />
          {verifyResult.note}
          {verifyResult.brokenAtSeq != null && (
            <span className="prov-broken-seq"> (seq={verifyResult.brokenAtSeq})</span>
          )}
          <button
            className="prov-banner-close"
            onClick={() => setVerifyResult(null)}
            title="关闭"
          >
            ×
          </button>
        </div>
      )}

      {/* ── 错误横幅 ── */}
      {error && (
        <div className="prov-error-banner">
          <span className="codicon codicon-warning" />
          加载失败：{error}
          <button className="prov-banner-close" onClick={() => setError(null)}>×</button>
        </div>
      )}

      {/* ── 统计行 ── */}
      <div className="prov-stats">
        共 {total} 条溯源记录
        {total > 0 && (
          <span className="prov-compliance-note">
            · EU AI Act Art.12 自动记录 · Art.19 留存≥400天 · Art.14 人类批准人可追溯
          </span>
        )}
      </div>

      {/* ── 时间线表格 ── */}
      {loading ? (
        <div className="prov-loading">
          <span className="codicon codicon-loading codicon-modifier-spin" />
          加载中…
        </div>
      ) : (
        <div className="prov-table-wrap">
          <table className="prov-table">
            <thead>
              <tr>
                <th>#</th>
                <th>时间</th>
                <th>文件</th>
                <th>操作</th>
                <th>模型</th>
                <th>批准人</th>
                <th>决策</th>
                <th>prompt 指纹</th>
              </tr>
            </thead>
            <tbody>
              {(page?.records ?? []).length === 0 ? (
                <tr>
                  <td colSpan={8} className="prov-empty-row">
                    暂无溯源记录。在 AI 对话中批准/拒绝变更后，账本将自动记录。
                  </td>
                </tr>
              ) : (
                (page?.records ?? []).map((r) => (
                  <>
                    <tr
                      key={r.id}
                      className={`prov-row ${expandedId === r.id ? "prov-row--expanded" : ""}`}
                      onClick={() => setExpandedId(expandedId === r.id ? null : r.id)}
                      title="点击展开详情"
                    >
                      <td className="prov-seq">{r.seq}</td>
                      <td className="prov-time">{formatDecidedAt(r.decidedAt)}</td>
                      <td className="prov-file" title={r.filePath ?? "—"}>
                        {r.filePath ? r.filePath.split("/").pop() : "—"}
                      </td>
                      <td className="prov-op">{r.filePath ?? "—"}</td>
                      <td className="prov-model" title={r.modelVersion ?? ""}>
                        {r.modelName ?? "未知"}
                      </td>
                      <td className="prov-approver">
                        {r.approverId != null ? `用户 #${r.approverId}` : "—"}
                      </td>
                      <td>
                        <span className={`prov-decision ${getDecisionClass(r.decision)}`}>
                          {formatDecision(r.decision)}
                        </span>
                      </td>
                      <td className="prov-fingerprint">
                        <code>{formatPromptFingerprint(r.promptHash)}</code>
                      </td>
                    </tr>
                    {expandedId === r.id && (
                      <tr key={`${r.id}-detail`} className="prov-detail-row">
                        <td colSpan={8}>
                          <ProvenanceDetailPanel record={r} />
                        </td>
                      </tr>
                    )}
                  </>
                ))
              )}
            </tbody>
          </table>
        </div>
      )}

      {/* ── 分页 ── */}
      {totalPages > 1 && (
        <div className="prov-pagination">
          <button
            className="prov-btn prov-btn--sm"
            disabled={currentPage === 0}
            onClick={() => setCurrentPage((p) => Math.max(0, p - 1))}
          >
            上一页
          </button>
          <span className="prov-page-info">
            {currentPage + 1} / {totalPages}
          </span>
          <button
            className="prov-btn prov-btn--sm"
            disabled={currentPage >= totalPages - 1}
            onClick={() => setCurrentPage((p) => Math.min(totalPages - 1, p + 1))}
          >
            下一页
          </button>
        </div>
      )}
    </div>
  );
}

/** 行展开详情面板：AI 侧 + 人工侧 + 哈希 */
function ProvenanceDetailPanel({ record: r }: { record: ProvenanceRecord }) {
  return (
    <div className="prov-detail">
      <div className="prov-detail-section">
        <div className="prov-detail-heading">AI 侧</div>
        <div className="prov-detail-grid">
          <span className="prov-kv-key">模型</span>
          <span className="prov-kv-val">{r.modelName ?? "—"} {r.modelVersion ? `(${r.modelVersion})` : ""}</span>
          <span className="prov-kv-key">供应商</span>
          <span className="prov-kv-val">{r.provider ?? "—"}</span>
          <span className="prov-kv-key">llmCallId</span>
          <span className="prov-kv-val">{r.llmCallId ?? "—"}</span>
          <span className="prov-kv-key">agentRunId</span>
          <span className="prov-kv-val">{r.agentRunId ?? "—"}</span>
          <span className="prov-kv-key">prompt 哈希</span>
          <span className="prov-kv-val">
            <code>{r.promptHash ?? "未知(历史变更)"}</code>
          </span>
          <span className="prov-kv-key">context 哈希</span>
          <span className="prov-kv-val">
            <code>{r.contextHash ?? "未知(历史变更)"}</code>
          </span>
        </div>
      </div>

      <div className="prov-detail-section">
        <div className="prov-detail-heading">人工侧</div>
        <div className="prov-detail-grid">
          <span className="prov-kv-key">批准人</span>
          <span className="prov-kv-val">{r.approverId != null ? `用户 #${r.approverId}` : "—"}</span>
          <span className="prov-kv-key">决策时间</span>
          <span className="prov-kv-val">{formatDecidedAt(r.decidedAt)}</span>
          <span className="prov-kv-key">决策</span>
          <span className="prov-kv-val">
            <span className={`prov-decision ${getDecisionClass(r.decision)}`}>
              {formatDecision(r.decision)}
            </span>
          </span>
          <span className="prov-kv-key">文件路径</span>
          <span className="prov-kv-val">{r.filePath ?? "—"}</span>
          <span className="prov-kv-key">diff 哈希</span>
          <span className="prov-kv-val">
            <code>{r.diffHash ?? "—"}</code>
          </span>
          <span className="prov-kv-key">changeId</span>
          <span className="prov-kv-val">{r.changeId ?? "—"}</span>
        </div>
      </div>

      <div className="prov-detail-section">
        <div className="prov-detail-heading">哈希链</div>
        <div className="prov-detail-grid">
          <span className="prov-kv-key">seq</span>
          <span className="prov-kv-val">{r.seq}</span>
          <span className="prov-kv-key">prev_hash</span>
          <span className="prov-kv-val prov-hash-wrap">
            <code>{r.prevHash ?? "—"}</code>
          </span>
          <span className="prov-kv-key">record_hash</span>
          <span className="prov-kv-val prov-hash-wrap">
            <code>{r.recordHash ?? "—"}</code>
          </span>
        </div>
      </div>

      {r.complianceNote && (
        <div className="prov-compliance-chip">{r.complianceNote}</div>
      )}
    </div>
  );
}
