import axios, { AxiosError, AxiosHeaders, type AxiosRequestConfig, type AxiosResponse } from 'axios';

export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';

/** 当前用户 ID：优先 localStorage，其次环境变量，最后默认 1。不再硬编码。 */
export function getCurrentUserId(): string {
  return localStorage.getItem('repolens.userId') || (import.meta.env.VITE_USER_ID as string) || '1';
}

interface BackendResult<T> {
  code: number;
  message: string;
  data?: T;
  timestamp?: string;
}

function isBackendResult<T>(payload: unknown): payload is BackendResult<T> {
  return Boolean(payload && typeof payload === 'object' && 'code' in payload && 'message' in payload);
}

export const request = axios.create({
  baseURL: API_BASE_URL,
  timeout: 60000
});

request.interceptors.request.use((config) => {
  if (!config.headers) {
    config.headers = new AxiosHeaders();
  }
  config.headers.set('X-User-Id', getCurrentUserId());
  return config;
});

request.interceptors.response.use(
  (response: AxiosResponse) => {
    const payload = response.data;
    // 后端统一返回 Result(code/message/data/timestamp)，前端在这里集中解包。
    if (isBackendResult(payload)) {
      if (payload.code !== 0) {
        throw new Error(payload.message || `接口返回错误 code=${payload.code}`);
      }
      return payload.data;
    }
    return payload;
  },
  (error: AxiosError<BackendResult<unknown>>) => {
    const payload = error.response?.data;
    if (isBackendResult(payload)) {
      return Promise.reject(new Error(payload.message || `接口返回错误 code=${payload.code}`));
    }
    return Promise.reject(new Error(error.message || '请求后端失败'));
  }
);

export function apiGet<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
  return request.get<BackendResult<T>, T>(url, config);
}

export function apiPost<T, B = unknown>(url: string, body?: B, config?: AxiosRequestConfig): Promise<T> {
  return request.post<BackendResult<T>, T, B>(url, body, config);
}
