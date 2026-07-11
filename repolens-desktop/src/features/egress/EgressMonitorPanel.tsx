import { useEffect, useState } from "react";
import {
  getPrivacyStatus,
  getEgressLogs,
  updatePrivacyMode,
  getPurposeLabel,
  type PrivacyMode,
  type EgressLogEntry,
} from "../../api/privacyApi";
import "./egress.css";

/**
 * 出网监控面板（独立活动栏工具）。
 *
 * 从设置页搬迁而来：负责「出网模式切换 + 出网统计 + 出网记录明细」。
 * 记录明细与统计每 10s 轮询刷新；出网模式单选属于配置，用户未保存前不被轮询覆盖。
 */
export function EgressMonitorPanel() {
  const [privacyMode, setPrivacyMode] = useState<PrivacyMode>("OPEN");
  const [privacyAllowlist, setPrivacyAllowlist] = useState("");
  const [privacyStats, setPrivacyStats] = useState<{
    total: number;
    blocked: number;
    allowed: number;
  } | null>(null);
  const [egressLogs, setEgressLogs] = useState<EgressLogEntry[]>([]);
  const [savingPrivacy, setSavingPrivacy] = useState(false);
  const [privacyMsg, setPrivacyMsg] = useState<string | null>(null);
  const [privacyError, setPrivacyError] = useState<string | null>(null);

  /**
   * 加载隐私状态与出网日志。
   * @param syncMode 是否用后端值覆盖本地的 mode/allowlist 选择。
   *   - 首次加载 / 保存后 / 手动刷新时传 true（同步真源）；
   *   - 定时轮询时传 false：只刷新统计与日志，绝不覆盖用户尚未保存的单选选择，
   *     否则用户点了单选后会被轮询「改回去」，表现为「点击无效」。
   */
  async function loadPrivacy(syncMode: boolean = false) {
    try {
      const status = await getPrivacyStatus();
      if (syncMode) {
        setPrivacyMode(status.mode);
        if (status.allowlist != null) {
          setPrivacyAllowlist(status.allowlist);
        }
      }
      setPrivacyStats({
        total: status.totalCount,
        blocked: status.blockedCount,
        allowed: status.allowedCount,
      });
    } catch {
      // fail silently — panel shows stale data
    }
    try {
      const logs = await getEgressLogs(50);
      setEgressLogs(logs);
    } catch {
      // fail silently
    }
  }

  async function handleSavePrivacy() {
    try {
      setSavingPrivacy(true);
      setPrivacyMsg(null);
      setPrivacyError(null);
      const status = await updatePrivacyMode({
        mode: privacyMode,
        allowlist: privacyMode === "ALLOWLIST" ? privacyAllowlist : undefined,
      });
      setPrivacyMode(status.mode);
      if (status.allowlist != null) {
        setPrivacyAllowlist(status.allowlist);
      }
      setPrivacyStats({
        total: status.totalCount,
        blocked: status.blockedCount,
        allowed: status.allowedCount,
      });
      setPrivacyMsg("隐私模式已保存，即时生效");
    } catch (e: unknown) {
      setPrivacyError(e instanceof Error ? e.message : "保存失败");
    } finally {
      setSavingPrivacy(false);
    }
  }

  useEffect(() => {
    // 首次加载：用后端真源同步当前模式与白名单。
    void loadPrivacy(true);
    // 之后每 10s 只刷新统计/日志，不覆盖用户尚未保存的模式选择。
    const timer = setInterval(() => void loadPrivacy(false), 10_000);
    return () => clearInterval(timer);
  }, []);

  const MODE_OPTIONS: { value: PrivacyMode; icon: string; desc: string }[] = [
    { value: "LOCAL_ONLY", icon: "🔒", desc: "本地专用 · 代码0出网 · 拦截所有非本机请求" },
    { value: "ALLOWLIST", icon: "🛡", desc: "白名单模式 · 仅允许回环地址或白名单内主机" },
    { value: "OPEN", icon: "☁", desc: "开放模式 · 全部放行并记录审计日志" },
  ];

  return (
    <div className="egress-view">
      <div className="egress-scroll">
        <h1 className="egress-title">出网监控</h1>
        <p className="egress-subtitle">
          实时监控本应用发起的出站网络连接，验证代码是否 0 字节出网。
        </p>

        {/* 出网模式（配置） */}
        <section className="egress-section">
          <div className="egress-section-head">
            <h2>出网模式</h2>
            <p className="egress-section-desc">
              切换后需点击「保存模式」生效；LOCAL_ONLY 下所有非本机出网请求将被拦截。
            </p>
          </div>

          <div className="egress-mode-group">
            {MODE_OPTIONS.map((opt) => (
              <label
                key={opt.value}
                className={`egress-mode-option${privacyMode === opt.value ? " selected" : ""}`}
              >
                <input
                  type="radio"
                  name="egressPrivacyMode"
                  value={opt.value}
                  checked={privacyMode === opt.value}
                  onChange={() => setPrivacyMode(opt.value)}
                />
                <span className="egress-mode-icon">{opt.icon}</span>
                <span className="egress-mode-body">
                  <span className="egress-mode-label">{opt.value}</span>
                  <span className="egress-mode-desc">{opt.desc}</span>
                </span>
              </label>
            ))}
          </div>

          {privacyMode === "ALLOWLIST" && (
            <div className="egress-field">
              <label>白名单域名</label>
              <p className="egress-field-desc">
                每行或逗号分隔一个域名/IP（精确匹配），如 <code>api.deepseek.com</code>。
              </p>
              <textarea
                className="egress-allowlist-editor"
                placeholder={"api.deepseek.com\napi.openai.com"}
                value={privacyAllowlist}
                onChange={(e) => setPrivacyAllowlist(e.target.value)}
                rows={4}
              />
            </div>
          )}

          <div className="egress-actions">
            <button
              className="egress-btn egress-btn-primary"
              onClick={() => void handleSavePrivacy()}
              disabled={savingPrivacy}
            >
              {savingPrivacy ? "保存中…" : "保存模式"}
            </button>
            <button className="egress-btn" onClick={() => void loadPrivacy(true)}>
              刷新记录
            </button>
            {privacyMsg && <span className="egress-test ok">✓ {privacyMsg}</span>}
            {privacyError && <span className="egress-test fail">✗ {privacyError}</span>}
          </div>
        </section>

        {/* 出网统计 */}
        {privacyStats && (
          <div className="egress-stats-row">
            <span className="egress-stat">
              共 <strong>{privacyStats.total}</strong> 条记录
            </span>
            <span className="egress-stat egress-stat-blocked">
              拦截 <strong>{privacyStats.blocked}</strong>
            </span>
            <span className="egress-stat egress-stat-allowed">
              放行 <strong>{privacyStats.allowed}</strong>
            </span>
          </div>
        )}

        {/* 出网记录明细 */}
        <section className="egress-section">
          <div className="egress-section-head">
            <h2>出网记录（最近 50 条）</h2>
            <p className="egress-section-desc">
              实时展示本应用发起的出站连接；
              <span className="egress-blocked-hint">红色行</span>
              表示已被当前模式拦截。
            </p>
          </div>

          {egressLogs.length === 0 ? (
            <p className="egress-hint">暂无出网记录。</p>
          ) : (
            <div className="egress-table-wrap">
              <table className="egress-table">
                <thead>
                  <tr>
                    <th>时间</th>
                    <th>用途</th>
                    <th>目标主机</th>
                    <th>状态</th>
                    <th>模式</th>
                  </tr>
                </thead>
                <tbody>
                  {egressLogs.map((entry) => (
                    <tr key={entry.id} className={!entry.allowed ? "egress-row-blocked" : ""}>
                      <td className="egress-cell-ts">
                        {entry.ts
                          ? new Date(entry.ts).toLocaleTimeString("zh-CN", { hour12: false })
                          : "-"}
                      </td>
                      <td>{getPurposeLabel(entry.purpose)}</td>
                      <td className="egress-cell-host" title={entry.resolvedIp ?? undefined}>
                        {entry.destHost}
                        {entry.destPort != null && `:${entry.destPort}`}
                        {entry.loopback && <span className="egress-loopback-badge">本机</span>}
                      </td>
                      <td>
                        {entry.allowed ? (
                          <span className="egress-status-allowed">放行</span>
                        ) : (
                          <span className="egress-status-blocked">已拦截</span>
                        )}
                      </td>
                      <td className="egress-cell-mode">{entry.privacyMode}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      </div>
    </div>
  );
}
