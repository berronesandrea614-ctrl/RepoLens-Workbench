import axios from "axios";

const BASE = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080";

// Auth endpoints that should NOT trigger a forced logout/reload on 401/409.
// Login 401 means wrong credentials; /me 401 means the token check itself.
// Register 409 means duplicate username — all handled by callers, not the interceptor.
export const AUTH_PASSTHROUGH_PATHS = ["/api/auth/login", "/api/auth/me", "/api/auth/register"] as const;

/** Returns true if the given URL is an auth endpoint whose 401 the caller handles. */
export function isAuthPassthrough(url: string): boolean {
  return AUTH_PASSTHROUGH_PATHS.some((path) => url.includes(path));
}

// 默认 30s 超时，避免请求永久挂起（后端故障时快速失败而非无限转圈）。
export const http = axios.create({ baseURL: BASE, timeout: 30000 });

http.interceptors.request.use((config) => {
  const token = localStorage.getItem("repolens.token");
  if (token) {
    config.headers["Authorization"] = `Bearer ${token}`;
  }
  return config;
});

type AxiosLikeError = {
  response?: { status?: number; data?: { message?: string } };
  config?: { url?: string };
  message?: string;
};

export function handleResponseError(err: unknown): Promise<never> {
  const axiosErr = err as AxiosLikeError;
  const status = axiosErr?.response?.status;
  if (status === 401 || status === 409) {
    const url = axiosErr?.config?.url ?? "";
    if (!isAuthPassthrough(url) && status === 401) {
      // 非 auth 路径的 401 → 清 token + 回登录页（简单可靠）
      localStorage.removeItem("repolens.token");
      localStorage.removeItem("repolens.userId");
      localStorage.removeItem("repolens.username");
      window.location.reload();
    }
    // auth 路径（login/me/register）的 401/409 → 交给调用方处理，不强制刷新
  }
  return Promise.reject(
    new Error(axiosErr?.response?.data?.message ?? axiosErr?.message ?? "request failed"),
  );
}

http.interceptors.response.use(
  (resp) => {
    const body = resp.data;
    if (body && typeof body === "object" && "code" in body) {
      if (body.code !== 0) throw new Error(body.message ?? "request failed");
      return body.data;
    }
    return body;
  },
  handleResponseError,
);
