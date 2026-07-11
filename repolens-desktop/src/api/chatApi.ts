import { http } from "./http";
import { AgentStep, AskQuestion, CodeAnswer, CodeAnswerPayload, CodeReference, FileChange, StreamHandlers } from "../types/chat";

export async function answerCodeQuestion(
  repoId: number,
  payload: CodeAnswerPayload,
): Promise<CodeAnswer> {
  return (await http.post(`/api/repos/${repoId}/chat/answer`, payload)) as unknown as CodeAnswer;
}

// 与 http 客户端保持同一 base URL / 身份头。EventSource 不能 POST，
// 故用 fetch + ReadableStream 手动解析 SSE 帧（按空行分帧，逐帧读 event: / data:）。
const BASE = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080";

function authHeaders(): Record<string, string> {
  const token = localStorage.getItem("repolens.token");
  return token ? { Authorization: `Bearer ${token}` } : {};
}

/**
 * 流式代码问答。逐 token 触发 handlers.onToken，结束时 onDone 携带完整 CodeAnswer。
 * 任何网络/解析失败都走 onError；调用方据此可回落到非流式 answerCodeQuestion。
 *
 * 取消：可传入 AbortSignal。abort 后 fetch/reader 立即中止，且 onToken/onDone/onError
 * 都不再触发（避免过期流写入已切换的会话）。调用方通过 controller.abort() 取消。
 */
export async function answerCodeQuestionStream(
  repoId: number,
  payload: CodeAnswerPayload,
  handlers: StreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  // abort 后一律不再回调，防止过期流污染当前状态。
  const aborted = () => signal?.aborted === true;

  let response: Response;
  try {
    response = await fetch(`${BASE}/api/repos/${repoId}/chat/answer/stream`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Accept: "text/event-stream",
        ...authHeaders(),
      },
      body: JSON.stringify(payload),
      signal,
    });
  } catch (e) {
    if (!aborted()) handlers.onError?.(e instanceof Error ? e.message : String(e));
    return;
  }

  if (aborted()) return;

  if (!response.ok || !response.body) {
    handlers.onError?.(`stream request failed: ${response.status}`);
    return;
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  const dispatch = (rawFrame: string) => {
    if (aborted()) return; // 过期流：丢弃所有帧，不触发回调。
    // 一帧可能有多行 data:，按 SSE 规范用换行拼接。
    let eventName = "message";
    const dataLines: string[] = [];
    for (const line of rawFrame.split("\n")) {
      if (line.startsWith("event:")) {
        eventName = line.slice("event:".length).trim();
      } else if (line.startsWith("data:")) {
        dataLines.push(line.slice("data:".length).replace(/^ /, ""));
      }
    }
    if (dataLines.length === 0) return;
    const data = dataLines.join("\n");
    let parsed: unknown;
    try {
      parsed = JSON.parse(data);
    } catch {
      return;
    }
    switch (eventName) {
      case "meta":
        handlers.onMeta?.(parsed as { references: CodeReference[]; modelName?: string });
        break;
      case "token":
        handlers.onToken?.((parsed as { text?: string }).text ?? "");
        break;
      case "step":
        // 单步增量事件（流式 agent 路径），逐步追加轨迹。
        handlers.onStep?.(parsed as AgentStep);
        break;
      case "steps":
        // 批量步骤事件（非流式回落路径），一次性覆盖轨迹。
        handlers.onSteps?.(
          parsed as { agentSteps?: AgentStep[]; agentIterations?: number; agentToolCalls?: number },
        );
        break;
      case "file_change":
        // 实时改动高亮事件：逐文件推送 before/after 全文，前端在编辑器 inline diff。
        handlers.onFileChange?.(parsed as FileChange);
        break;
      case "ask":
        // askUser 反问事件：agent 挂起等待用户回复，前端弹提问卡，回复经 /agent/answer 回传唤醒。
        handlers.onAsk?.(parsed as AskQuestion);
        break;
      case "done":
        handlers.onDone?.(parsed as CodeAnswer);
        break;
      case "error":
        handlers.onError?.((parsed as { message?: string }).message ?? "stream error");
        break;
      default:
        break;
    }
  };

  try {
    for (;;) {
      if (aborted()) return; // 取消后立即停止读取。
      const { done, value } = await reader.read();
      if (done) break;
      // 归一 CRLF，避免不同容器换行导致分帧失败（SSE data 为 JSON，无裸 CR）。
      buffer += decoder.decode(value, { stream: true }).replace(/\r/g, "");
      // SSE 事件以空行（\n\n）分隔。
      let idx: number;
      while ((idx = buffer.indexOf("\n\n")) !== -1) {
        const frame = buffer.slice(0, idx);
        buffer = buffer.slice(idx + 2);
        if (frame.trim().length > 0) dispatch(frame);
      }
    }
    // 冲刷末尾残留帧（若服务端未以空行收尾）。
    if (buffer.trim().length > 0) dispatch(buffer);
  } catch (e) {
    // abort 触发的 read() 拒绝不算错误，静默退出。
    if (!aborted()) handlers.onError?.(e instanceof Error ? e.message : String(e));
  }
}
