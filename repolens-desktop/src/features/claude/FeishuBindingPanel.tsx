/**
 * FeishuBindingPanel — Modal panel for managing Feishu remote-control bindings.
 *
 * Props:
 *   repoId   — the repo to manage bindings for
 *   onClose  — called when the user closes the panel
 *
 * Layout:
 *   ┌─────────────────────────────────────────────────────────┐
 *   │ Header: 飞书远程控制                          [✕]       │
 *   │ ⚠ Privacy notice bar                                   │
 *   ├────────────────────────────┬────────────────────────────┤
 *   │ Left: form + bindings list │ Right: 操作指南 (guide)    │
 *   └────────────────────────────┴────────────────────────────┘
 */

import { useEffect, useState } from "react";
import {
  listBindings,
  createBinding,
  deleteBinding,
  testConnection,
  statusToColor,
  statusLabel,
  type FeishuBinding,
} from "../../api/feishuApi";
import { useClaudeStore } from "../../state/claudeStore";

interface Props {
  repoId: number;
  onClose: () => void;
}

export function FeishuBindingPanel({ repoId, onClose }: Props) {
  const setFeishuBindings = useClaudeStore((s) => s.setFeishuBindings);

  // ── Form state ────────────────────────────────────────────
  const [botName, setBotName] = useState("");
  const [appId, setAppId] = useState("");
  const [appSecret, setAppSecret] = useState("");

  // ── List state ────────────────────────────────────────────
  const [bindings, setBindings] = useState<FeishuBinding[]>([]);
  const [loading, setLoading] = useState(true);

  // ── Action feedback ───────────────────────────────────────
  const [testMsg, setTestMsg] = useState<string | null>(null);
  const [testOk, setTestOk] = useState<boolean | null>(null);
  const [bindError, setBindError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [testing, setTesting] = useState(false);

  // ── Load bindings on mount + poll for live status ─────────
  // silent=true：轮询刷新时不显示 loading、不清空已有列表（避免闪烁 / 网络抖动清空）。
  async function loadBindings(silent = false) {
    if (!silent) setLoading(true);
    try {
      const list = await listBindings(repoId);
      setBindings(list);
      setFeishuBindings(repoId, list);
    } catch {
      if (!silent) setBindings([]);
    } finally {
      if (!silent) setLoading(false);
    }
  }

  useEffect(() => {
    void loadBindings();
    // 面板打开期间每 4s 静默刷新绑定状态：后端 WS 连上后，「已连接」会自动反映，
    // 无需手动点绑定/删除触发刷新（修复「状态不自动刷新、要手动点一下」）。
    const timer = setInterval(() => void loadBindings(true), 4000);
    return () => clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [repoId]);

  // ── Handlers ──────────────────────────────────────────────

  async function handleTest() {
    if (!appId.trim() || !appSecret.trim()) {
      setTestMsg("请填写 App ID 和 App Secret");
      setTestOk(false);
      return;
    }
    setTesting(true);
    setTestMsg(null);
    try {
      const ok = await testConnection(repoId, appId.trim(), appSecret.trim());
      setTestOk(ok);
      setTestMsg(ok ? "✓ 连接成功，凭证有效" : "✗ 连接失败，请检查 App ID / Secret");
    } catch (err) {
      setTestOk(false);
      setTestMsg("✗ 测试请求失败：" + (err instanceof Error ? err.message : String(err)));
    } finally {
      setTesting(false);
    }
  }

  async function handleBind() {
    if (!botName.trim() || !appId.trim() || !appSecret.trim()) {
      setBindError("请填写全部三个字段");
      return;
    }
    setSubmitting(true);
    setBindError(null);
    try {
      await createBinding(repoId, {
        botName: botName.trim(),
        appId: appId.trim(),
        appSecret: appSecret.trim(),
      });
      // Reset form and reload list
      setBotName("");
      setAppId("");
      setAppSecret("");
      setTestMsg(null);
      setTestOk(null);
      await loadBindings();
    } catch (err) {
      setBindError("绑定失败：" + (err instanceof Error ? err.message : String(err)));
    } finally {
      setSubmitting(false);
    }
  }

  async function handleDelete(id: number) {
    setBindError(null);
    try {
      await deleteBinding(repoId, id);
      await loadBindings();
    } catch (err) {
      // 明确提示失败原因（此前静默吞掉，导致「删除没反应」）。
      const msg = err instanceof Error ? err.message : String(err);
      setBindError(
        /403|forbidden|无权限/i.test(msg)
          ? "删除失败：无权限。飞书绑定只能由创建它的账号删除（请用当初绑定的账号登录）。"
          : "删除失败：" + msg,
      );
      console.warn("[FeishuBindingPanel] deleteBinding error:", err);
    }
  }

  // ── Render ────────────────────────────────────────────────

  return (
    <div
      className="feishu-overlay"
      role="dialog"
      aria-modal="true"
      aria-label="飞书远程控制"
      onClick={onClose}
    >
      <div className="feishu-modal" onClick={(e) => e.stopPropagation()}>

        {/* Header */}
        <div className="feishu-modal-header">
          <span>🔗 飞书远程控制</span>
          <button
            className="feishu-close-btn"
            onClick={onClose}
            aria-label="关闭"
          >
            ✕
          </button>
        </div>

        {/* Privacy notice */}
        <div className="feishu-privacy-bar">
          ⚠ 飞书链路会把你的指令与 Claude 反馈经飞书云中转，不属于「代码不出内网」范围，请知悉
        </div>

        {/* Content area: two columns */}
        <div className="feishu-content">

          {/* Left: form + bindings */}
          <div className="feishu-form-panel">
            <div className="feishu-section-title">新增绑定</div>

            <div className="feishu-input-group">
              <label className="feishu-label">机器人名称</label>
              <input
                className="feishu-input"
                type="text"
                placeholder="例：开发助手机器人"
                value={botName}
                onChange={(e) => setBotName(e.target.value)}
                disabled={submitting}
              />
            </div>

            <div className="feishu-input-group">
              <label className="feishu-label">App ID</label>
              <input
                className="feishu-input"
                type="text"
                placeholder="cli_xxxxxxxxxxxxxx"
                value={appId}
                onChange={(e) => {
                  setAppId(e.target.value);
                  setTestMsg(null);
                }}
                disabled={submitting}
              />
            </div>

            <div className="feishu-input-group">
              <label className="feishu-label">App Secret</label>
              <input
                className="feishu-input"
                type="password"
                placeholder="App Secret（仅用于首次绑定，不存储）"
                value={appSecret}
                onChange={(e) => {
                  setAppSecret(e.target.value);
                  setTestMsg(null);
                }}
                disabled={submitting}
              />
            </div>

            {/* Test result message */}
            {testMsg && (
              <div
                className="feishu-test-msg"
                style={{ color: testOk ? "#3fb950" : "#f85149" }}
              >
                {testMsg}
              </div>
            )}

            {/* Bind error */}
            {bindError && (
              <div className="feishu-test-msg" style={{ color: "#f85149" }}>
                {bindError}
              </div>
            )}

            <div className="feishu-btn-row">
              <button
                className="feishu-btn feishu-btn--secondary"
                onClick={() => void handleTest()}
                disabled={testing || submitting}
              >
                {testing ? "测试中…" : "测试连接"}
              </button>
              <button
                className="feishu-btn feishu-btn--primary"
                onClick={() => void handleBind()}
                disabled={submitting || testing}
              >
                {submitting ? "绑定中…" : "绑定"}
              </button>
            </div>

            {/* Existing bindings list */}
            <div className="feishu-section-title feishu-section-title--mt">
              已有绑定
              {loading && <span className="feishu-loading"> 加载中…</span>}
            </div>

            {!loading && bindings.length === 0 && (
              <div className="feishu-empty">暂无绑定</div>
            )}

            {bindings.map((b) => (
              <div key={b.id} className="feishu-binding-item">
                <div className="feishu-binding-main">
                  <span className="feishu-binding-name">{b.botName}</span>
                  <span
                    className="feishu-status-badge"
                    style={{
                      background: statusToColor(b.status) + "22",
                      color: statusToColor(b.status),
                      borderColor: statusToColor(b.status) + "66",
                    }}
                  >
                    {statusLabel(b.status)}
                  </span>
                </div>
                <div className="feishu-binding-sub">
                  <span className="feishu-binding-appid" title={b.appId}>
                    {b.appId}
                  </span>
                  {b.lastError && (
                    <span className="feishu-binding-error" title={b.lastError}>
                      {b.lastError}
                    </span>
                  )}
                </div>
                <div className="feishu-binding-actions">
                  <button
                    className="feishu-btn feishu-btn--danger feishu-btn--sm"
                    onClick={() => void handleDelete(b.id)}
                  >
                    删除
                  </button>
                </div>
              </div>
            ))}
          </div>

          {/* Right: usage guide */}
          <div className="feishu-guide-panel">
            <div className="feishu-section-title">飞书接入指南</div>
            <ol className="feishu-guide-list">
              <li>
                <strong>创建应用</strong>
                <p>
                  访问{" "}
                  <span className="feishu-guide-url">open.feishu.cn</span>{" "}
                  → 开发者后台 → 创建企业自建应用，在「功能」→「机器人」中开启机器人能力。
                </p>
              </li>
              <li>
                <strong>配置事件与权限</strong>
                <p>
                  进入「事件与回调」，订阅模式选择{" "}
                  <em>长连接</em>，添加事件{" "}
                  <code>im.message.receive_v1</code>；
                  在「权限管理」中申请{" "}
                  <code>im:message</code> 和{" "}
                  <code>im:message:send_as_bot</code>{" "}
                  权限（无需发布即可内测）。
                </p>
              </li>
              <li>
                <strong>绑定并使用</strong>
                <p>
                  复制「凭证与基础信息」中的 App ID / App Secret，
                  填入左侧输入框点击「绑定」。绑定后把机器人拉进飞书群聊或单聊，
                  在手机/飞书客户端发消息即可向当前 Claude 窗口发送指令。
                </p>
              </li>
            </ol>
            <div className="feishu-guide-note">
              提示：绑定成功后状态为「已连接」时，Claude 的终端输出也会同步回推到飞书对话中。
            </div>
          </div>

        </div>
      </div>
    </div>
  );
}
