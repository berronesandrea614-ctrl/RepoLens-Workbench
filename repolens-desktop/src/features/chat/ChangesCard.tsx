import { useEffect, useRef, useState } from "react";
import { iconForFile } from "../../ui/fileIcons";
import { useWorkbench } from "../../state/workbenchStore";
import { disposeModelFor } from "../editor/models";
import {
  applyAllChanges,
  applyChange,
  fetchChanges,
  rejectAllChanges,
  rejectChange,
  revertChange,
} from "../../api/changeApi";
import {
  checkDependencies,
  DependencyCheckResult,
  getDependencyChecks,
  getVerdictLabel,
  getVerdictSeverity,
  parseTyposquatDetail,
} from "../../api/dependencyApi";
import {
  acknowledgeRisk,
  ChangeRisk,
  isBlockedRow,
  listRisks,
  rowRiskLevel,
} from "../../api/riskApi";
import { FileChangeSummary } from "../../types/chat";
import { ChangeStatus, FileChangeDetail } from "../../types/change";
import { DiffModal } from "./DiffModal";

interface ChangesCardProps {
  repoId: number;
  sessionId?: number;
  changes: FileChangeSummary[];
}

const baseName = (p: string) => p.split("/").pop() ?? p;

const STATUS_LABEL: Record<ChangeStatus, string> = {
  PROPOSED: "待确认",
  APPLIED: "已应用",
  REJECTED: "已拒绝",
  REVERTED: "已撤销",
};

/** 该状态的行是否置灰（终态，无可执行动作，仅可看 diff）。 */
export const isTerminalStatus = (s: ChangeStatus) => s === "REJECTED" || s === "REVERTED";

/**
 * 编码模式回答后的「审批面板」：本次改动均为 **提议（PROPOSED）**，尚未落盘。
 * 用户必须逐个或整体点击「应用」才会真正写入磁盘——这是信任锚点。
 */
export function ChangesCard({ repoId, sessionId, changes }: ChangesCardProps) {
  const openFile = useWorkbench((s) => s.openFile);
  const markIndexStale = useWorkbench((s) => s.markIndexStale);
  const refreshTree = useWorkbench((s) => s.refreshTree);

  // 每个 changeId 的当前审批状态；初始全部为「提议中」。
  const [statuses, setStatuses] = useState<Record<number, ChangeStatus>>(() => {
    const init: Record<number, ChangeStatus> = {};
    for (const c of changes) init[c.changeId] = "PROPOSED";
    return init;
  });
  // 正在处理的 changeId，或 "all" 表示批量操作进行中。
  const [busy, setBusy] = useState<number | "all" | null>(null);
  // 拉取一次会话全部改动详情后缓存，供 diff 复用。
  const [details, setDetails] = useState<FileChangeDetail[]>();
  const [diff, setDiff] = useState<{ filePath: string; old: string; neu: string } | null>(null);
  const [diffLoading, setDiffLoading] = useState(false);
  const [rowError, setRowError] = useState<string>();
  const [notice, setNotice] = useState<string>();

  // ─── 依赖体检状态 ──────────────────────────────────────────────────────────
  const [depResults, setDepResults] = useState<DependencyCheckResult[]>([]);
  const [depLoading, setDepLoading] = useState(false);
  // 防重入：避免 changes 变化多次触发并发加载
  const depLoadingRef = useRef(false);

  /** 拉取/刷新体检结果（失败静默，不影响审批操作）。 */
  async function loadDepResults(forceRecheck = false) {
    if (!sessionId || depLoadingRef.current) return;
    depLoadingRef.current = true;
    setDepLoading(true);
    try {
      let results: DependencyCheckResult[];
      if (forceRecheck) {
        results = await checkDependencies(repoId, { sessionId });
      } else {
        results = await getDependencyChecks(repoId, sessionId);
      }
      setDepResults(results);
    } catch {
      // 体检失败静默处理，不影响审批 UI
    } finally {
      setDepLoading(false);
      depLoadingRef.current = false;
    }
  }

  // 挂载时 + changes 变化时拉取体检结果
  useEffect(() => {
    void loadDepResults(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId, changes.length]);

  // ─── 风险检测状态 ──────────────────────────────────────────────────────────
  // changeId → 该行的风险列表
  const [risksByChangeId, setRisksByChangeId] = useState<Record<number, ChangeRisk[]>>({});
  const [riskLoading, setRiskLoading] = useState(false);
  // 每行的用户 ack 勾选状态（changeId → boolean）
  const [ackedRows, setAckedRows] = useState<Record<number, boolean>>({});
  const riskLoadingRef = useRef(false);

  /** 拉取风险列表（失败静默，不影响审批操作）。 */
  async function loadRisks() {
    if (!sessionId || riskLoadingRef.current) return;
    riskLoadingRef.current = true;
    setRiskLoading(true);
    try {
      const risks = await listRisks(repoId, sessionId);
      const byId: Record<number, ChangeRisk[]> = {};
      for (const r of risks) {
        if (!byId[r.changeId]) byId[r.changeId] = [];
        byId[r.changeId].push(r);
      }
      setRisksByChangeId(byId);
    } catch {
      // 风险加载失败静默处理
    } finally {
      setRiskLoading(false);
      riskLoadingRef.current = false;
    }
  }

  // 挂载时 + changes 变化时拉取风险
  useEffect(() => {
    void loadRisks();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId, changes.length]);

  // ─── 体检摘要计算 ─────────────────────────────────────────────────────────
  const depByFile: Record<string, DependencyCheckResult[]> = {};
  for (const r of depResults) {
    if (!depByFile[r.filePath]) depByFile[r.filePath] = [];
    depByFile[r.filePath].push(r);
  }

  const notFoundCount = depResults.filter((r) => r.verdict === "NOT_FOUND").length;
  const typosquatCount = depResults.filter((r) => r.verdict === "TYPOSQUAT").length;
  const maliciousCount = depResults.filter((r) => r.verdict === "MALICIOUS").length;
  const vulnerableCount = depResults.filter((r) => r.verdict === "VULNERABLE").length;
  const totalNew = depResults.length;
  const hasProblems = notFoundCount > 0 || typosquatCount > 0 || maliciousCount > 0 || vulnerableCount > 0;
  const depChecked = depResults.length > 0;
  const isOffline = depResults.length > 0 && depResults.every((r) => r.checkedOffline);

  const statusOf = (id: number): ChangeStatus => statuses[id] ?? "PROPOSED";
  const pending = changes.filter((c) => statusOf(c.changeId) === "PROPOSED");

  function setStatus(id: number, status: ChangeStatus) {
    setStatuses((prev) => ({ ...prev, [id]: status }));
  }

  // ─── 风险摘要计算 ─────────────────────────────────────────────────────────
  const allRisks = Object.values(risksByChangeId).flat();
  // 含 BLOCK 风险的 changeId 数量（用于横幅显示）
  const blockCount = new Set(
    allRisks.filter((r) => r.severity === "BLOCK").map((r) => r.changeId),
  ).size;
  const hasBlockRisk = blockCount > 0;
  // 仅有 WARN（无 BLOCK）→ 黄色横幅
  const hasWarnRiskOnly =
    !hasBlockRisk && allRisks.some((r) => r.severity === "WARN");
  // 是否存在 pending 行有未确认的 BLOCK+IRREVERSIBLE 风险
  const hasUnconfirmedBlock = pending.some((ch) =>
    isBlockedRow(
      risksByChangeId[ch.changeId] ?? [],
      ackedRows[ch.changeId] ?? false,
    ),
  );
  // 「全部应用」是否需要带 ack=true（有任何 pending 行含 BLOCK+IRREVERSIBLE）
  const applyAllNeedsAck = pending.some((ch) =>
    (risksByChangeId[ch.changeId] ?? []).some(
      (r) => r.severity === "BLOCK" && r.reversibility === "IRREVERSIBLE",
    ),
  );

  async function ensureDetails(): Promise<FileChangeDetail[]> {
    if (details) return details;
    if (sessionId == null) return [];
    const fetched = await fetchChanges(repoId, sessionId);
    setDetails(fetched);
    return fetched;
  }

  function findDetail(list: FileChangeDetail[], ch: FileChangeSummary) {
    return (
      list.find((d) => d.id === ch.changeId) ??
      list.find((d) => d.filePath === ch.filePath)
    );
  }

  /** 磁盘已被改动（应用 / 撤销）：刷新已打开文件 + 文件树 + 索引标记。 */
  function refreshDisk(filePath: string) {
    disposeModelFor(repoId, filePath);
    markIndexStale();
    refreshTree();
    // 详情缓存作废，下次 diff 重新拉取最新内容/状态。
    setDetails(undefined);
  }

  /** 勾选「不可逆操作确认框」时更新 ackedRows 并通知后端（失败静默）。 */
  function handleAck(changeId: number, checked: boolean) {
    setAckedRows((prev) => ({ ...prev, [changeId]: checked }));
    if (checked) {
      void acknowledgeRisk(repoId, changeId).catch(() => {
        // 通知后端失败静默，前端 ack 状态已更新，不影响审批流程
      });
    }
  }

  async function onViewDiff(ch: FileChangeSummary) {
    setRowError(undefined);
    setDiffLoading(true);
    setDiff({ filePath: ch.filePath, old: "", neu: "" });
    try {
      const list = await ensureDetails();
      const d = findDetail(list, ch);
      if (!d) {
        setDiff(null);
        setRowError("未找到该文件的改动详情");
        return;
      }
      setDiff({ filePath: d.filePath, old: d.oldContent ?? "", neu: d.newContent ?? "" });
    } catch (e: unknown) {
      setDiff(null);
      setRowError(e instanceof Error ? e.message : String(e));
    } finally {
      setDiffLoading(false);
    }
  }

  /** 应用单条改动。ack=true 表示用户已勾选「不可逆操作」确认框。 */
  async function onApply(ch: FileChangeSummary, ack = false) {
    if (busy != null) return;
    setRowError(undefined);
    setNotice(undefined);
    setBusy(ch.changeId);
    try {
      await applyChange(repoId, ch.changeId, ack);
      setStatus(ch.changeId, "APPLIED");
      refreshDisk(ch.filePath);
      setNotice(`已应用：${baseName(ch.filePath)}`);
    } catch (e: unknown) {
      setRowError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(null);
    }
  }

  async function onReject(ch: FileChangeSummary) {
    if (busy != null) return;
    setRowError(undefined);
    setNotice(undefined);
    setBusy(ch.changeId);
    try {
      await rejectChange(repoId, ch.changeId);
      // 拒绝不改磁盘，仅更新状态。
      setStatus(ch.changeId, "REJECTED");
      setDetails(undefined);
      setNotice(`已拒绝：${baseName(ch.filePath)}`);
    } catch (e: unknown) {
      setRowError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(null);
    }
  }

  async function onRevert(ch: FileChangeSummary) {
    if (busy != null) return;
    setRowError(undefined);
    setNotice(undefined);
    setBusy(ch.changeId);
    try {
      await revertChange(repoId, ch.changeId);
      setStatus(ch.changeId, "REVERTED");
      refreshDisk(ch.filePath);
      setNotice(`已撤销：${baseName(ch.filePath)}`);
    } catch (e: unknown) {
      setRowError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(null);
    }
  }

  async function onApplyAll() {
    if (busy != null || sessionId == null || pending.length === 0) return;
    setRowError(undefined);
    setNotice(undefined);
    setBusy("all");
    try {
      await applyAllChanges(repoId, sessionId, applyAllNeedsAck);
      for (const ch of pending) {
        setStatus(ch.changeId, "APPLIED");
        disposeModelFor(repoId, ch.filePath);
      }
      markIndexStale();
      refreshTree();
      setDetails(undefined);
      setNotice(`已应用全部 ${pending.length} 个改动`);
    } catch (e: unknown) {
      setRowError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(null);
    }
  }

  async function onRejectAll() {
    if (busy != null || sessionId == null || pending.length === 0) return;
    setRowError(undefined);
    setNotice(undefined);
    setBusy("all");
    try {
      await rejectAllChanges(repoId, sessionId);
      // 全部拒绝不改磁盘。
      for (const ch of pending) setStatus(ch.changeId, "REJECTED");
      setDetails(undefined);
      setNotice(`已拒绝全部 ${pending.length} 个改动`);
    } catch (e: unknown) {
      setRowError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(null);
    }
  }

  const hasPending = pending.length > 0;

  return (
    <div className={`changes-card ${hasPending ? "pending" : ""}`}>
      {hasPending ? (
        <div className="changes-approval-head">
          <div className="changes-approval-title">
            <span className="codicon codicon-shield" />
            AI 提议修改 {pending.length} 个文件（尚未落盘，需你确认）
          </div>
          <div className="changes-approval-note">
            未点击「应用」前，任何内容都不会写入磁盘。
          </div>
          <div className="changes-approval-actions">
            <button
              className="changes-btn primary"
              onClick={() => void onApplyAll()}
              disabled={busy != null || sessionId == null || hasUnconfirmedBlock}
              title={hasUnconfirmedBlock ? "请先逐条确认破坏性操作" : undefined}
            >
              {busy === "all" ? "处理中…" : "全部应用"}
            </button>
            <button
              className="changes-btn danger"
              onClick={() => void onRejectAll()}
              disabled={busy != null || sessionId == null}
            >
              全部拒绝
            </button>
          </div>
        </div>
      ) : (
        <div className="changes-head">本次修改 {changes.length} 个文件</div>
      )}

      {rowError && <div className="changes-error">{rowError}</div>}
      {notice && <div className="changes-notice">{notice}</div>}

      {/* 风险横幅（并列于依赖体检横幅）*/}
      {sessionId != null && !riskLoading && (hasBlockRisk || hasWarnRiskOnly) && (
        <div className={`risk-check-banner ${hasBlockRisk ? "risk-banner-block" : "risk-banner-warn"}`}>
          {hasBlockRisk ? (
            <span>⚠ {blockCount} 项破坏性操作需确认</span>
          ) : (
            <span>⚠ 存在风险提示，请检查各文件</span>
          )}
        </div>
      )}

      {/* 依赖体检横幅 */}
      {sessionId != null && (
        <div className="dep-check-banner">
          {depLoading ? (
            <span className="dep-check-loading">依赖体检中…</span>
          ) : depChecked ? (
            <>
              {hasProblems ? (
                <span className="dep-check-warn">
                  ⚠ 依赖体检：新增 {totalNew} 个依赖，
                  {maliciousCount > 0 && `${maliciousCount} 个恶意`}
                  {maliciousCount > 0 && (notFoundCount > 0 || typosquatCount > 0 || vulnerableCount > 0) && "·"}
                  {notFoundCount > 0 && `${notFoundCount} 个不存在`}
                  {notFoundCount > 0 && (typosquatCount > 0 || vulnerableCount > 0) && "·"}
                  {typosquatCount > 0 && `${typosquatCount} 个疑似抢注`}
                  {typosquatCount > 0 && vulnerableCount > 0 && "·"}
                  {vulnerableCount > 0 && `${vulnerableCount} 个有漏洞`}
                </span>
              ) : (
                <span className="dep-check-ok">✅ 依赖体检通过</span>
              )}
              {isOffline && (
                <span className="dep-offline-badge">OFFLINE</span>
              )}
            </>
          ) : null}
          <button
            className="changes-btn dep-recheck-btn"
            onClick={() => void loadDepResults(true)}
            disabled={depLoading || !sessionId}
            title="仅发送包名到公共 registry，不含代码"
          >
            {depLoading ? "体检中…" : "重新体检"}
          </button>
          {depChecked && (
            <span className="dep-check-privacy">
              仅发送包名到公共 registry
            </span>
          )}
        </div>
      )}

      {changes.map((ch) => {
        const { icon, color } = iconForFile(baseName(ch.filePath));
        const status = statusOf(ch.changeId);
        const terminal = isTerminalStatus(status);
        const rowBusy = busy === ch.changeId;
        // 该文件的依赖体检问题
        const fileDepResults = depByFile[ch.filePath] ?? [];
        const fileProblems = fileDepResults.filter(
          (r) =>
            r.verdict === "MALICIOUS" ||
            r.verdict === "NOT_FOUND" ||
            r.verdict === "TYPOSQUAT" ||
            r.verdict === "VULNERABLE",
        );
        // 该行的风险信息
        const rowRisks = risksByChangeId[ch.changeId] ?? [];
        const riskLevel = rowRiskLevel(rowRisks);
        const isAcked = ackedRows[ch.changeId] ?? false;
        const rowIsBlocked = isBlockedRow(rowRisks, isAcked);
        const blockRisks = rowRisks.filter(
          (r) => r.severity === "BLOCK" && r.reversibility === "IRREVERSIBLE",
        );

        return (
          <div
            key={ch.changeId}
            className={[
              "changes-row",
              terminal ? "reverted" : "",
              riskLevel === "BLOCK" ? "risk-block" : "",
              riskLevel === "WARN" ? "risk-warn" : "",
            ]
              .filter(Boolean)
              .join(" ")}
          >
            <span className="changes-icon" style={{ color }}>{icon}</span>
            <span className="changes-path" title={ch.filePath}>{ch.filePath}</span>
            <span className={`changes-badge ${status.toLowerCase()}`}>{STATUS_LABEL[status]}</span>
            <span className="changes-actions">
              <button className="changes-btn" onClick={() => void onViewDiff(ch)}>查看 diff</button>
              {status === "PROPOSED" && (
                <>
                  <button
                    className="changes-btn primary"
                    onClick={() => void onApply(ch, isAcked)}
                    disabled={busy != null || rowIsBlocked}
                    title={rowIsBlocked ? "请先勾选「不可逆操作」确认框" : undefined}
                  >
                    {rowBusy ? "应用中…" : "应用"}
                  </button>
                  <button
                    className="changes-btn danger"
                    onClick={() => void onReject(ch)}
                    disabled={busy != null}
                  >
                    拒绝
                  </button>
                </>
              )}
              {status === "APPLIED" && (
                <>
                  <button className="changes-btn" onClick={() => openFile(ch.filePath)}>打开</button>
                  <button
                    className="changes-btn danger"
                    onClick={() => void onRevert(ch)}
                    disabled={busy != null}
                  >
                    {rowBusy ? "撤销中…" : "撤销"}
                  </button>
                </>
              )}
            </span>
            {/* 文件级依赖体检明细 */}
            {fileProblems.length > 0 && (
              <div className="dep-check-file-flags">
                {fileProblems.map((r) => {
                  const severity = getVerdictSeverity(r.verdict);
                  const label = getVerdictLabel(r.verdict);
                  if (r.verdict === "TYPOSQUAT") {
                    const detail = parseTyposquatDetail(r.detailJson);
                    return (
                      <span key={r.id} className={`dep-flag dep-flag-${severity}`}>
                        {label} {r.packageName}
                        {detail.suggestion ? ` → 建议 ${detail.suggestion}` : ""}
                      </span>
                    );
                  }
                  return (
                    <span key={r.id} className={`dep-flag dep-flag-${severity}`}>
                      {label} {r.packageName}
                    </span>
                  );
                })}
              </div>
            )}
            {/* 破坏性操作风险明细 + 确认框（仅 BLOCK+IRREVERSIBLE 行） */}
            {blockRisks.length > 0 && (
              <div className="risk-row-detail">
                {blockRisks.map((r, i) => (
                  <div key={i} className="risk-evidence">
                    <span className="risk-rule-code">[{r.ruleCode}]</span>
                    <span className="risk-evidence-text">{r.evidence}</span>
                  </div>
                ))}
                {status === "PROPOSED" && (
                  <label className="risk-ack-label">
                    <input
                      type="checkbox"
                      className="risk-ack-checkbox"
                      checked={isAcked}
                      onChange={(e) => handleAck(ch.changeId, e.target.checked)}
                    />
                    ⚠ 我已了解这是不可逆操作，确认应用
                  </label>
                )}
              </div>
            )}
          </div>
        );
      })}

      {diff && (
        <DiffModal
          filePath={diff.filePath}
          oldContent={diff.old}
          newContent={diff.neu}
          loading={diffLoading}
          onClose={() => setDiff(null)}
        />
      )}
    </div>
  );
}
