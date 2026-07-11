import { useEffect, useState } from "react";
import { AppShell } from "./layout/AppShell";
import { LoginView } from "./features/auth/LoginView";
import { getMe } from "./api/authApi";

function hasToken(): boolean {
  return !!localStorage.getItem("repolens.token");
}

export default function App() {
  const [authenticated, setAuthenticated] = useState<boolean | null>(null);

  useEffect(() => {
    if (!hasToken()) {
      setAuthenticated(false);
      return;
    }
    // 后台校验 token 有效性
    getMe()
      .then(() => setAuthenticated(true))
      .catch(() => {
        localStorage.removeItem("repolens.token");
        localStorage.removeItem("repolens.userId");
        localStorage.removeItem("repolens.username");
        setAuthenticated(false);
      });
  }, []);

  if (authenticated === null) {
    // 初始化中 - 短暂空白，避免闪烁
    return null;
  }

  if (!authenticated) {
    return <LoginView onLogin={() => setAuthenticated(true)} />;
  }

  return <AppShell />;
}
