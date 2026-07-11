import { http } from "./http";
import { FileContent } from "../types/file";

export async function fetchFileContent(repoId: number, filePath: string): Promise<string> {
  const data = (await http.get("/api/files/content", {
    params: { repoId, filePath, startLine: 1, endLine: 100000 },
  })) as unknown as FileContent;
  return data.content ?? "";
}

export interface FileWriteResult {
  filePath: string;
  bytes: number;
}

export async function saveFileContent(
  repoId: number,
  filePath: string,
  content: string,
): Promise<FileWriteResult> {
  return (await http.put("/api/files/content", {
    repoId,
    filePath,
    content,
  })) as unknown as FileWriteResult;
}

export function languageFromPath(path: string): string {
  if (path.endsWith(".java")) return "java";
  if (path.endsWith(".xml")) return "xml";
  if (path.endsWith(".json")) return "json";
  if (path.endsWith(".yml") || path.endsWith(".yaml")) return "yaml";
  if (path.endsWith(".sql")) return "sql";
  if (path.endsWith(".md")) return "markdown";
  if (path.endsWith(".ts") || path.endsWith(".tsx")) return "typescript";
  if (path.endsWith(".js")) return "javascript";
  if (path.endsWith(".css")) return "css";
  if (path.endsWith(".html")) return "html";
  if (path.endsWith(".properties")) return "ini";
  return "plaintext";
}
