import { http } from "./http";

export interface LoginResponse {
  token: string;
  userId: number;
  username: string;
}

export interface UserInfo {
  userId: number;
  username: string;
  displayName?: string;
}

export async function login(username: string, password: string): Promise<LoginResponse> {
  return (await http.post("/api/auth/login", { username, password })) as unknown as LoginResponse;
}

export async function register(
  username: string,
  password: string,
  displayName?: string,
): Promise<LoginResponse> {
  return (await http.post("/api/auth/register", { username, password, displayName })) as unknown as LoginResponse;
}

export async function getMe(): Promise<UserInfo> {
  return (await http.get("/api/auth/me")) as unknown as UserInfo;
}

export async function changePassword(oldPassword: string, newPassword: string): Promise<void> {
  await http.post("/api/auth/password", { oldPassword, newPassword });
}
