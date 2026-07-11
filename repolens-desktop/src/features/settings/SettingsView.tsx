import { useEffect, useState } from "react";
import {
  getLlmSettings,
  saveLlmSettings,
  testLlmConnection,
} from "../../api/settingsApi";
import { changePassword } from "../../api/authApi";
import {
  verifyPrivacyChain,
  downloadPrivacyReport,
  checkIcon,
  checkClass,
  summarizeVerifyResult,
  type PrivacyVerifyResult,
} from "../../api/privacyApi";
import { useI18n } from "../../i18n/I18nProvider";
import "./settings.css";

/**
 * LLM Provider 预设。所有非 mock/ollama 的 provider 都走后端 OpenAiCompatibleLlmClient
 * （统一 OpenAI Chat Completions 协议，仅 baseUrl/model 不同）。
 * - baseUrl: 选中该 provider 时自动填入的默认 API 基础地址（用户可再改）。
 * - models: Model 输入的候选下拉（datalist），可自填。
 * - hint:   Base URL 下方的补充说明。
 */
interface ProviderPreset {
  value: string;
  label: string;
  baseUrl?: string;
  models?: string[];
  hint?: string;
}

const PROVIDERS: ProviderPreset[] = [
  { value: "mock", label: "mock（内置模拟，无需配置）" },
  {
    value: "deepseek",
    label: "DeepSeek",
    baseUrl: "https://api.deepseek.com",
    models: ["deepseek-chat", "deepseek-reasoner"],
    hint: "DeepSeek 官方 OpenAI 兼容接口。",
  },
  {
    value: "openai",
    label: "OpenAI",
    baseUrl: "https://api.openai.com/v1",
    models: ["gpt-4o", "gpt-4o-mini", "gpt-4.1", "gpt-4.1-mini", "o4-mini"],
    hint: "OpenAI 官方接口。",
  },
  {
    value: "anthropic",
    label: "Anthropic Claude（需 OpenAI 兼容网关）",
    baseUrl: "https://api.anthropic.com/v1",
    models: [
      "claude-sonnet-4-6",
      "claude-opus-4-8",
      "claude-3-5-sonnet-latest",
      "claude-3-5-haiku-latest",
    ],
    hint: "本应用走 OpenAI Chat Completions 协议，非 Anthropic 原生 /messages。需使用支持 OpenAI 格式的 Anthropic 兼容网关（如 one-api / new-api / LiteLLM 等）作为 Base URL。",
  },
  {
    value: "gemini",
    label: "Google Gemini（OpenAI 兼容端点）",
    baseUrl: "https://generativelanguage.googleapis.com/v1beta/openai/",
    models: ["gemini-2.0-flash", "gemini-1.5-pro", "gemini-1.5-flash"],
    hint: "Google 官方提供的 OpenAI 兼容端点。",
  },
  {
    value: "qwen",
    label: "通义千问 Qwen（阿里云）",
    baseUrl: "https://dashscope.aliyuncs.com/compatible-mode/v1",
    models: ["qwen-plus", "qwen-max", "qwen-turbo", "qwen2.5-coder-32b-instruct"],
    hint: "阿里云 DashScope OpenAI 兼容模式。",
  },
  {
    value: "zhipu",
    label: "智谱 GLM",
    baseUrl: "https://open.bigmodel.cn/api/paas/v4",
    models: ["glm-4-plus", "glm-4-air", "glm-4-flash"],
    hint: "智谱 AI OpenAI 兼容接口。",
  },
  {
    value: "moonshot",
    label: "月之暗面 Kimi / Moonshot",
    baseUrl: "https://api.moonshot.cn/v1",
    models: ["moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k"],
    hint: "月之暗面 Moonshot OpenAI 兼容接口。",
  },
  {
    value: "ollama",
    label: "Ollama（本地）",
    baseUrl: "http://localhost:11434/v1",
    models: ["qwen2.5-coder", "llama3.1", "deepseek-r1"],
    hint: "本地 Ollama 的 OpenAI 兼容端点，无需 API Key。",
  },
  {
    value: "openai-compatible",
    label: "openai-compatible（自定义兼容端点）",
    hint: "任意 OpenAI Chat Completions 兼容网关，手动填写 Base URL / Model。",
  },
];

/** 按 value 取预设。 */
function providerPreset(value: string): ProviderPreset | undefined {
  return PROVIDERS.find((p) => p.value === value);
}

/** 所有预设 baseUrl 集合，用于判断当前 baseUrl 是否为"某个预设的默认值"。 */
const PRESET_BASE_URLS = new Set(
  PROVIDERS.map((p) => p.baseUrl).filter((u): u is string => !!u),
);

interface TestState {
  ok: boolean;
  message: string;
}

function handleLogout() {
  localStorage.removeItem("repolens.token");
  localStorage.removeItem("repolens.userId");
  localStorage.removeItem("repolens.username");
  window.location.reload();
}

export function SettingsView() {
  // 界面语言：直接驱动全局 i18n Context（setLocale 写回 localStorage 并即时切换全局界面）。
  const { t, locale, setLocale } = useI18n();

  const [provider, setProvider] = useState("mock");

  function handleLocaleChange(next: "zh" | "en") {
    setLocale(next);
  }

  // 修改密码
  const [oldPassword, setOldPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [pwdMsg, setPwdMsg] = useState<string | null>(null);
  const [pwdError, setPwdError] = useState<string | null>(null);
  const [changingPwd, setChangingPwd] = useState(false);

  const currentUsername = localStorage.getItem("repolens.username") ?? "用户";

  async function handleChangePassword() {
    if (!oldPassword || !newPassword) {
      setPwdError("请填写旧密码和新密码");
      return;
    }
    try {
      setChangingPwd(true);
      setPwdMsg(null);
      setPwdError(null);
      await changePassword(oldPassword, newPassword);
      setPwdMsg("密码修改成功");
      setOldPassword("");
      setNewPassword("");
    } catch (e: unknown) {
      setPwdError(e instanceof Error ? e.message : "修改失败");
    } finally {
      setChangingPwd(false);
    }
  }
  const [baseUrl, setBaseUrl] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [modelName, setModelName] = useState("");
  const [timeoutMs, setTimeoutMs] = useState(30000);
  const [apiKeyMasked, setApiKeyMasked] = useState("");

  /**
   * 切换 Provider：若目标 provider 有预设 baseUrl，且当前 baseUrl 为空或仍是"某个预设的默认值"
   * （即用户没自定义过），就自动填入新预设的 baseUrl，方便用户开箱即用；用户自填过的 baseUrl 不覆盖。
   */
  function handleProviderChange(nextValue: string) {
    setProvider(nextValue);
    const preset = providerPreset(nextValue);
    if (preset?.baseUrl) {
      setBaseUrl((prev) => {
        const untouched = prev.trim() === "" || PRESET_BASE_URLS.has(prev.trim());
        return untouched ? preset.baseUrl! : prev;
      });
    }
  }

  const currentPreset = providerPreset(provider);

  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<TestState | null>(null);

  const [saving, setSaving] = useState(false);
  const [saveMsg, setSaveMsg] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);

  // ─── G-P2: Verify & Report state ──────────────────────────────────────────
  const [verifying, setVerifying] = useState(false);
  const [verifyResult, setVerifyResult] = useState<PrivacyVerifyResult | null>(null);
  const [verifyError, setVerifyError] = useState<string | null>(null);
  const [downloading, setDownloading] = useState(false);
  const [downloadMsg, setDownloadMsg] = useState<string | null>(null);

  async function handleVerify() {
    try {
      setVerifying(true);
      setVerifyError(null);
      const result = await verifyPrivacyChain();
      setVerifyResult(result);
    } catch (e: unknown) {
      setVerifyError(e instanceof Error ? e.message : "校验请求失败");
    } finally {
      setVerifying(false);
    }
  }

  async function handleDownloadReport() {
    try {
      setDownloading(true);
      setDownloadMsg(null);
      await downloadPrivacyReport("txt");
      setDownloadMsg("证明报告已下载");
      setTimeout(() => setDownloadMsg(null), 3000);
    } catch (e: unknown) {
      setDownloadMsg("下载失败：" + (e instanceof Error ? e.message : String(e)));
    } finally {
      setDownloading(false);
    }
  }

  async function load() {
    try {
      setLoading(true);
      setLoadError(null);
      const s = await getLlmSettings();
      setProvider(s.provider || "mock");
      setBaseUrl(s.baseUrl || "");
      setModelName(s.modelName || "");
      setTimeoutMs(s.timeoutMs || 30000);
      setApiKeyMasked(s.apiKeyMasked || "");
      setApiKey("");
    } catch (e: any) {
      setLoadError(e?.message ?? String(e));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load();
  }, []);

  async function handleTest() {
    try {
      setTesting(true);
      setTestResult(null);
      const r = await testLlmConnection({ provider, baseUrl, apiKey, modelName });
      setTestResult({ ok: r.ok, message: r.message });
    } catch (e: any) {
      setTestResult({ ok: false, message: e?.message ?? String(e) });
    } finally {
      setTesting(false);
    }
  }

  async function handleSave() {
    try {
      setSaving(true);
      setSaveMsg(null);
      setSaveError(null);
      // Empty apiKey keeps the existing key on the backend.
      const s = await saveLlmSettings({ provider, baseUrl, apiKey, modelName, timeoutMs });
      setProvider(s.provider || "mock");
      setBaseUrl(s.baseUrl || "");
      setModelName(s.modelName || "");
      setTimeoutMs(s.timeoutMs || 30000);
      setApiKeyMasked(s.apiKeyMasked || "");
      setApiKey("");
      setSaveMsg("已保存，即时生效");
    } catch (e: any) {
      setSaveError(e?.message ?? String(e));
    } finally {
      setSaving(false);
    }
  }

  const apiKeyPlaceholder = apiKeyMasked
    ? `已保存 ${apiKeyMasked}，留空则不修改`
    : "输入 API Key";

  return (
    <div className="settings-view">
      <div className="settings-scroll">
        <h1 className="settings-title">{t("settings.title", "设置")}</h1>

        {loading && <div className="settings-hint">{t("common.loading", "加载中…")}</div>}
        {loadError && <div className="settings-error">加载失败：{loadError}</div>}

        {!loading && !loadError && (
          <div className="settings-section">
            <div className="settings-section-head">
              <h2>{t("settings.sectionLlm", "语言模型 (LLM)")}</h2>
              <p className="settings-section-desc">
                配置用于 AI 会话与代码理解的大语言模型。支持本地 Ollama 或任意 OpenAI 兼容接口，密钥仅保存在本地后端。
              </p>
            </div>

            <div className="settings-field">
              <label>Provider</label>
              <p className="settings-field-desc">
                选择模型提供方预设。选中后会自动填入对应的 Base URL 与 Model 候选（可再修改）。
              </p>
              <select value={provider} onChange={(e) => handleProviderChange(e.target.value)}>
                {/* 兼容已保存的旧 provider 值（如 deepseek-compatible）：不在预设中时补一个回显项，
                    避免受控 select 落到首项而悄悄改掉用户已保存的选择。 */}
                {!currentPreset && (
                  <option value={provider}>{provider}（当前已保存）</option>
                )}
                {PROVIDERS.map((p) => (
                  <option key={p.value} value={p.value}>
                    {p.label}
                  </option>
                ))}
              </select>
            </div>

            {provider !== "mock" && (
              <>
            <div className="settings-field">
              <label>Base URL</label>
              <p className="settings-field-desc">
                {currentPreset?.hint ?? "API 基础地址。"}
              </p>
              <input
                type="text"
                placeholder={currentPreset?.baseUrl ?? "https://api.deepseek.com"}
                value={baseUrl}
                onChange={(e) => setBaseUrl(e.target.value)}
              />
            </div>

            <div className="settings-field">
              <label>API Key</label>
              <p className="settings-field-desc">
                {provider === "ollama"
                  ? "本地 Ollama 无需 API Key，可留空。"
                  : "留空则保留已保存的密钥。"}
              </p>
              <input
                type="password"
                placeholder={apiKeyPlaceholder}
                value={apiKey}
                onChange={(e) => setApiKey(e.target.value)}
              />
            </div>

            <div className="settings-field">
              <label>Model</label>
              <p className="settings-field-desc">模型名称，可从候选中选择或直接自填。</p>
              <input
                type="text"
                list="llm-model-options"
                placeholder={currentPreset?.models?.[0] ?? "deepseek-chat / qwen2.5-coder"}
                value={modelName}
                onChange={(e) => setModelName(e.target.value)}
              />
              <datalist id="llm-model-options">
                {(currentPreset?.models ?? []).map((m) => (
                  <option key={m} value={m} />
                ))}
              </datalist>
            </div>

            <div className="settings-field">
              <label>Timeout (ms)</label>
              <p className="settings-field-desc">请求超时时间，单位毫秒。</p>
              <input
                type="number"
                className="settings-number"
                value={timeoutMs}
                onChange={(e) => setTimeoutMs(Number(e.target.value))}
              />
            </div>
              </>
            )}

            <div className="settings-actions">
              <button className="settings-btn" onClick={handleTest} disabled={testing}>
                {testing ? t("settings.testing", "测试中…") : t("settings.testConnection", "测试连接")}
              </button>
              <button
                className="settings-btn settings-btn-primary"
                onClick={handleSave}
                disabled={saving}
              >
                {saving ? t("common.saving", "保存中…") : t("settings.save", "保存")}
              </button>
              {testResult && (
                <span className={`settings-test ${testResult.ok ? "ok" : "fail"}`}>
                  {testResult.ok ? `✓ 连接成功` : `✗ ${testResult.message}`}
                </span>
              )}
              {saveMsg && <span className="settings-test ok">✓ {saveMsg}</span>}
              {saveError && <span className="settings-test fail">✗ {saveError}</span>}
            </div>

            <div className="settings-section-head settings-embedding">
              <h2>{t("settings.sectionEmbedding", "向量 (Embedding)")}</h2>
              <p className="settings-section-desc">
                Embedding provider 通过环境变量配置（本地 Ollama BGE），详见 README。
              </p>
            </div>

            <div className="settings-section-head settings-embedding">
              <h2>{t("settings.sectionRules", "项目规则 (AGENTS.md)")}</h2>
              <p className="settings-section-desc">
                支持 AGENTS.md：在仓库根目录放置 <code>AGENTS.md</code>（或 <code>.repolens/rules.md</code>），AI 会自动加载项目规则，无需额外配置。
              </p>
            </div>
          </div>
        )}

        {/* G-P2: 一键校验 + 导出证明 */}
        <div className="settings-section" style={{ borderTop: "1px solid var(--vs-border)", paddingTop: "18px", marginTop: "8px" }}>
          <div className="settings-section-head">
            <h2>{t("settings.sectionVerify", "链路校验 & 零出网证明")}</h2>
            <p className="settings-section-desc">
              校验本地 AI 链路的四项指标，并导出可存档的应用层出网证明。
              <span className="settings-privacy-note">
                （应用层快照；完整证明需配合 JFR/OS 层交叉验证，见导出报告中的说明）
              </span>
            </p>
          </div>

          <div className="settings-actions">
            <button
              className="settings-btn settings-btn-primary"
              onClick={() => void handleVerify()}
              disabled={verifying}
            >
              {verifying ? "校验中…" : "一键校验本地链路"}
            </button>
            <button
              className="settings-btn"
              onClick={() => void handleDownloadReport()}
              disabled={downloading}
            >
              {downloading ? "导出中…" : "导出0出网证明"}
            </button>
            {downloadMsg && (
              <span className={`settings-test ${downloadMsg.startsWith("下载失败") ? "fail" : "ok"}`}>
                {downloadMsg}
              </span>
            )}
            {verifyError && <span className="settings-test fail">✗ {verifyError}</span>}
          </div>

          {verifyResult && (
            <div className="verify-result-panel">
              <div className={`verify-verdict ${verifyResult.verdict ? "verify-verdict--pass" : "verify-verdict--fail"}`}>
                {verifyResult.verdict ? "✓ 校验通过" : "✗ 校验未通过"}
                <span className="verify-verdict-mode"> 模式: {verifyResult.mode}</span>
              </div>
              <p className="verify-summary">{summarizeVerifyResult(verifyResult)}</p>
              <table className="verify-checks-table">
                <thead>
                  <tr>
                    <th>检查项</th>
                    <th>结果</th>
                    <th>说明</th>
                  </tr>
                </thead>
                <tbody>
                  <tr className={checkClass(verifyResult.llmProviderIsLocal)}>
                    <td>LLM Provider 本地化</td>
                    <td>{checkIcon(verifyResult.llmProviderIsLocal)}</td>
                    <td>{verifyResult.llmProviderIsLocal?.reason}</td>
                  </tr>
                  <tr className={checkClass(verifyResult.baseUrlIsLoopback)}>
                    <td>BaseUrl 回环验证</td>
                    <td>{checkIcon(verifyResult.baseUrlIsLoopback)}</td>
                    <td>{verifyResult.baseUrlIsLoopback?.reason}</td>
                  </tr>
                  <tr className={checkClass(verifyResult.ollamaReachable)}>
                    <td>Ollama 端点可达</td>
                    <td>{checkIcon(verifyResult.ollamaReachable)}</td>
                    <td>{verifyResult.ollamaReachable?.reason}</td>
                  </tr>
                  <tr className={checkClass(verifyResult.recentEgressAllExternalBlocked)}>
                    <td>出网日志外网放行为零</td>
                    <td>{checkIcon(verifyResult.recentEgressAllExternalBlocked)}</td>
                    <td>{verifyResult.recentEgressAllExternalBlocked?.reason}</td>
                  </tr>
                </tbody>
              </table>
              {verifyResult.warnings && verifyResult.warnings.length > 0 && (
                <div className="verify-warnings">
                  <span>⚠ 警告: </span>
                  {verifyResult.warnings.join("; ")}
                </div>
              )}
              <p className="verify-note">{verifyResult.note}</p>
              <p className="verify-timestamp">校验时间: {verifyResult.checkedAt}</p>
            </div>
          )}
        </div>

        {/* 外观与语言 */}
        <div className="settings-section" style={{ borderTop: "1px solid var(--vs-border)", paddingTop: "18px", marginTop: "8px" }}>
          <div className="settings-section-head">
            <h2>{t("settings.sectionAppearance", "外观与语言")}</h2>
            <p className="settings-section-desc">
              界面语言偏好保存在本地。完整界面翻译由 i18n 逐步接入，切换后可能需重启应用生效。
            </p>
          </div>

          <div className="settings-field">
            <label>{t("settings.uiLanguage", "界面语言")}</label>
            <p className="settings-field-desc">{t("settings.uiLanguageDesc", "选择应用界面语言。")}</p>
            <select value={locale} onChange={(e) => handleLocaleChange(e.target.value as "zh" | "en")}>
              <option value="zh">{t("settings.langZh", "简体中文")}</option>
              <option value="en">{t("settings.langEn", "English")}</option>
            </select>
          </div>
        </div>

        {/* 账号管理 */}
        <div className="settings-section" style={{ borderTop: "1px solid var(--vs-border)", paddingTop: "18px", marginTop: "8px" }}>
          <div className="settings-section-head">
            <h2>{t("settings.sectionAccount", "账号管理")}</h2>
            <p className="settings-section-desc">当前登录用户：<strong>{currentUsername}</strong></p>
          </div>

          <div className="settings-field">
            <label>旧密码</label>
            <input
              type="password"
              placeholder="当前密码"
              value={oldPassword}
              onChange={(e) => setOldPassword(e.target.value)}
              disabled={changingPwd}
            />
          </div>
          <div className="settings-field">
            <label>新密码</label>
            <input
              type="password"
              placeholder="至少 6 位"
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
              disabled={changingPwd}
            />
          </div>
          <div className="settings-actions">
            <button className="settings-btn settings-btn-primary" onClick={handleChangePassword} disabled={changingPwd}>
              {changingPwd ? "修改中…" : t("settings.changePassword", "修改密码")}
            </button>
            {pwdMsg && <span className="settings-test ok">✓ {pwdMsg}</span>}
            {pwdError && <span className="settings-test fail">✗ {pwdError}</span>}
          </div>

          <div className="settings-actions" style={{ marginTop: "8px" }}>
            <button className="settings-btn" onClick={handleLogout}>
              {t("settings.logout", "退出登录")}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
