import { http } from "./http";

export interface LlmSettings {
  provider: string;
  baseUrl: string;
  modelName: string;
  timeoutMs: number;
  apiKeyMasked: string;
}

export interface SaveLlmRequest {
  provider: string;
  baseUrl: string;
  apiKey: string;
  modelName: string;
  timeoutMs: number;
}

export interface TestLlmRequest {
  provider: string;
  baseUrl: string;
  apiKey: string;
  modelName: string;
}

export interface TestLlmResult {
  ok: boolean;
  message: string;
}

export async function getLlmSettings(): Promise<LlmSettings> {
  return (await http.get("/api/settings/llm")) as unknown as LlmSettings;
}

export async function saveLlmSettings(req: SaveLlmRequest): Promise<LlmSettings> {
  return (await http.put("/api/settings/llm", req)) as unknown as LlmSettings;
}

export async function testLlmConnection(req: TestLlmRequest): Promise<TestLlmResult> {
  return (await http.post("/api/settings/llm/test", req)) as unknown as TestLlmResult;
}
