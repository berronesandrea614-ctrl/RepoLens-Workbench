import { useState } from "react";
import { login, register } from "../../api/authApi";
import {
  hasLoginErrors,
  hasRegisterErrors,
  validateLoginForm,
  validateRegisterForm,
} from "./loginValidation";
import "./login.css";

interface Props {
  onLogin: () => void;
}

type Mode = "login" | "register";

export function LoginView({ onLogin }: Props) {
  const [mode, setMode] = useState<Mode>("login");

  // Login fields
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");

  // Register extra fields
  const [displayName, setDisplayName] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");

  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  function switchMode(next: Mode) {
    setMode(next);
    setError(null);
    setUsername("");
    setPassword("");
    setDisplayName("");
    setConfirmPassword("");
  }

  async function handleLoginSubmit(e: React.FormEvent) {
    e.preventDefault();
    const errors = validateLoginForm(username, password);
    if (hasLoginErrors(errors)) {
      setError(errors.username ?? errors.password ?? "请填写完整");
      return;
    }
    try {
      setLoading(true);
      setError(null);
      const resp = await login(username.trim(), password);
      localStorage.setItem("repolens.token", resp.token);
      localStorage.setItem("repolens.userId", String(resp.userId));
      localStorage.setItem("repolens.username", resp.username);
      onLogin();
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "登录失败");
    } finally {
      setLoading(false);
    }
  }

  async function handleRegisterSubmit(e: React.FormEvent) {
    e.preventDefault();
    const errors = validateRegisterForm(username, password, confirmPassword);
    if (hasRegisterErrors(errors)) {
      setError(
        errors.username ?? errors.password ?? errors.confirmPassword ?? "请填写完整",
      );
      return;
    }
    try {
      setLoading(true);
      setError(null);
      const resp = await register(username.trim(), password, displayName.trim() || undefined);
      localStorage.setItem("repolens.token", resp.token);
      localStorage.setItem("repolens.userId", String(resp.userId));
      localStorage.setItem("repolens.username", resp.username);
      onLogin();
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "注册失败");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="login-overlay">
      <div className="login-card">
        <div className="login-logo">
          <span className="codicon codicon-repo" />
        </div>
        <h1 className="login-title">RepoLens</h1>
        <p className="login-subtitle">智能代码仓库分析平台</p>

        {/* Mode toggle tabs */}
        <div className="login-tabs">
          <button
            type="button"
            className={`login-tab${mode === "login" ? " login-tab--active" : ""}`}
            onClick={() => switchMode("login")}
            disabled={loading}
          >
            登录
          </button>
          <button
            type="button"
            className={`login-tab${mode === "register" ? " login-tab--active" : ""}`}
            onClick={() => switchMode("register")}
            disabled={loading}
          >
            注册
          </button>
        </div>

        {mode === "login" ? (
          <form className="login-form" onSubmit={handleLoginSubmit} noValidate>
            <div className="login-field">
              <label htmlFor="username">用户名</label>
              <input
                id="username"
                type="text"
                autoComplete="username"
                autoFocus
                placeholder="admin"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                disabled={loading}
              />
            </div>
            <div className="login-field">
              <label htmlFor="password">密码</label>
              <input
                id="password"
                type="password"
                autoComplete="current-password"
                placeholder="••••••••"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                disabled={loading}
              />
            </div>
            {error && <div className="login-error">{error}</div>}
            <button className="login-btn" type="submit" disabled={loading}>
              {loading ? "登录中…" : "登录"}
            </button>
          </form>
        ) : (
          <form className="login-form" onSubmit={handleRegisterSubmit} noValidate>
            <div className="login-field">
              <label htmlFor="reg-username">用户名 *</label>
              <input
                id="reg-username"
                type="text"
                autoComplete="username"
                autoFocus
                placeholder="3-64 个字符"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                disabled={loading}
              />
            </div>
            <div className="login-field">
              <label htmlFor="reg-displayname">显示名称（可选）</label>
              <input
                id="reg-displayname"
                type="text"
                autoComplete="name"
                placeholder="留空则使用用户名"
                value={displayName}
                onChange={(e) => setDisplayName(e.target.value)}
                disabled={loading}
              />
            </div>
            <div className="login-field">
              <label htmlFor="reg-password">密码 *</label>
              <input
                id="reg-password"
                type="password"
                autoComplete="new-password"
                placeholder="至少 6 位"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                disabled={loading}
              />
            </div>
            <div className="login-field">
              <label htmlFor="reg-confirm">确认密码 *</label>
              <input
                id="reg-confirm"
                type="password"
                autoComplete="new-password"
                placeholder="再次输入密码"
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                disabled={loading}
              />
            </div>
            {error && <div className="login-error">{error}</div>}
            <button className="login-btn" type="submit" disabled={loading}>
              {loading ? "注册中…" : "注册"}
            </button>
          </form>
        )}
      </div>
    </div>
  );
}
