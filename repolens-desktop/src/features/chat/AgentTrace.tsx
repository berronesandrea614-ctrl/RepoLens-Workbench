import { useState } from "react";
import { AgentStep } from "../../types/chat";
import { renderMarkdown } from "./ccMarkdown";

/**
 * Claude Code 风的执行流渲染：把 agent 的步骤按「轮次」聚成批次——
 * 每批上方是该轮的叙事（thought，像 Claude Code 的 ● 小结），下方是一行折叠的工具批次摘要
 * （「读取 1 · 运行命令 2」），点开才看每步细节。中间只管跑、想看再展开，避免铺一大堆。
 */
export function AgentTrace({ steps, streaming }: {
  steps?: AgentStep[]; iterations?: number; toolCalls?: number; streaming?: boolean;
}) {
  if (!steps || steps.length === 0) {
    return null;
  }
  const batches = groupSteps(steps);
  return (
    <div className="cc-flow">
      {batches.map((b, i) => (
        <BatchView key={i} batch={b} live={streaming && i === batches.length - 1} />
      ))}
    </div>
  );
}

interface Batch {
  thought: string;
  steps: AgentStep[];
}

function BatchView({ batch, live }: { batch: Batch; live?: boolean }) {
  const [open, setOpen] = useState(false);
  return (
    <div className="cc-batch">
      {batch.thought && <div className="cc-narration cc-md">{renderMarkdown(batch.thought)}</div>}
      <button className="cc-batch-head" onClick={() => setOpen((v) => !v)}>
        <span className="cc-chevron">{open ? "▾" : "▸"}</span>
        <span className="cc-batch-summary">{batchSummary(batch)}</span>
        {live && <span className="cc-batch-live">⟳</span>}
      </button>
      {open && (
        <div className="cc-batch-detail">
          {batch.steps.map((s) => (
            <StepDetail key={s.stepIndex} step={s} />
          ))}
        </div>
      )}
    </div>
  );
}

function StepDetail({ step }: { step: AgentStep }) {
  return (
    <div className="cc-step">
      <div className="cc-step-title">{stepSummary(step)}</div>
      {step.toolArgs && <div className="cc-step-row cc-mono">{step.toolArgs}</div>}
      {step.observation && <div className="cc-step-row">{trunc(step.observation, 600)}</div>}
    </div>
  );
}

/** 把连续、同一轮（thought 相同或后续步无 thought）的步骤聚成一个批次。 */
function groupSteps(steps: AgentStep[]): Batch[] {
  const batches: Batch[] = [];
  for (const s of steps) {
    const last = batches[batches.length - 1];
    const t = s.thought ?? "";
    if (last && (t === last.thought || t === "")) {
      last.steps.push(s);
    } else {
      batches.push({ thought: t, steps: [s] });
    }
  }
  return batches;
}

/** 批次摘要（Claude Code 风：按工具类别聚合计数，如「读取 1 · 运行命令 2 · 验证 1」）。 */
function batchSummary(batch: Batch): string {
  const order: string[] = [];
  const counts: Record<string, number> = {};
  for (const s of batch.steps) {
    const cat = toolCategory(s.toolName);
    if (!(cat in counts)) order.push(cat);
    counts[cat] = (counts[cat] || 0) + 1;
  }
  return order.map((c) => `${c} ${counts[c]}`).join(" · ");
}

function toolCategory(tool: string): string {
  switch (tool) {
    case "read": return "读取";
    case "write":
    case "edit":
    case "multi_edit": return "编辑";
    case "grep":
    case "glob": return "搜索";
    case "bash": return "运行命令";
    case "runVerification": return "验证";
    case "TodoWrite": return "整理清单";
    case "askUser": return "提问";
    case "Task": return "子代理";
    default: return tool;
  }
}

/**
 * 把一步工具调用折叠成一行人类可读摘要（Claude Code / Codex 风：动词 + 对象）。
 * 用于「执行中的单行进度」与步骤标题。
 */
export function stepSummary(s: AgentStep): string {
  const a = parseArgs(s.toolArgs);
  const str = (k: string) => (typeof a[k] === "string" ? (a[k] as string) : "");
  const base = (p: string) => (p ? p.split("/").pop() || p : "");
  switch (s.toolName) {
    case "read": return `读取 ${base(str("file_path"))}`;
    case "write": return `写入 ${base(str("file_path"))}`;
    case "edit":
    case "multi_edit": return `编辑 ${base(str("file_path"))}`;
    case "grep": return `搜索 "${str("pattern")}"`;
    case "glob": return `查找文件 ${str("pattern")}`;
    case "bash": return `运行命令 ${trunc(str("command"), 40)}`;
    case "runVerification": return "运行验证";
    case "TodoWrite": return "整理任务清单";
    case "askUser": return "向你提问";
    case "Task": return "调研子任务";
    default: return s.toolName;
  }
}

function parseArgs(raw?: string): Record<string, unknown> {
  if (!raw) return {};
  try {
    const v = JSON.parse(raw);
    return v && typeof v === "object" ? (v as Record<string, unknown>) : {};
  } catch {
    return {};
  }
}

function trunc(s: string, n: number): string {
  return s.length > n ? s.slice(0, n) + "…" : s;
}
