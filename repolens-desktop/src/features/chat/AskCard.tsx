import { useEffect, useMemo, useRef, useState } from "react";
import { AskQuestion, AskSubQuestion } from "../../types/chat";

/**
 * askUser 多选卡片（对标 Claude Code 的 AskUserQuestion）：
 * 顶部一排可左右切换的问题标签（… Submit），当前问题显示标题 + 候选选项（label 蓝 + description 灰），
 * 上下键选、Enter 确认并跳下一题、←→ 换题、Esc 取消；每题可选"其它"自由填；无选项的问题退化为自由文本。
 * 答完点 Submit（或走到 Submit 标签 Enter）合成回复回传。
 */
export function AskCard({
  ask,
  onSubmit,
  onCancel,
}: {
  ask: AskQuestion;
  onSubmit: (reply: string) => void;
  onCancel: () => void;
}) {
  const questions = ask.questions ?? [];
  const submitIdx = questions.length; // 最后一个"标签"是 Submit
  const [qIndex, setQIndex] = useState(0);
  const [optIndex, setOptIndex] = useState(0);
  // 每题的选中项（label 数组；单选只留一个）。其它自由文本单独存。
  const [picked, setPicked] = useState<Record<number, string[]>>({});
  const [otherText, setOtherText] = useState<Record<number, string>>({});
  const [otherOpen, setOtherOpen] = useState<Record<number, boolean>>({});
  const rootRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    rootRef.current?.focus();
  }, []);

  const cur: AskSubQuestion | undefined = questions[qIndex];
  // 当前题的可选项 = 后端给的 options + 一个"其它"
  const rows = useMemo(() => {
    const base = (cur?.options ?? []).map((o) => ({ label: o.label, description: o.description, other: false }));
    base.push({ label: "其它（自己填）", description: undefined, other: true });
    return base;
  }, [cur]);

  function togglePick(qi: number, label: string, multi: boolean) {
    setPicked((prev) => {
      const has = (prev[qi] ?? []).includes(label);
      if (multi) {
        const next = has ? (prev[qi] ?? []).filter((l) => l !== label) : [...(prev[qi] ?? []), label];
        return { ...prev, [qi]: next };
      }
      return { ...prev, [qi]: has ? [] : [label] };
    });
  }

  function chooseRow(rowIdx: number) {
    const row = rows[rowIdx];
    if (!row) return;
    const multi = !!cur?.multiSelect;
    if (row.other) {
      setOtherOpen((p) => ({ ...p, [qIndex]: !p[qIndex] }));
      return;
    }
    togglePick(qIndex, row.label, multi);
    if (!multi) advance();
  }

  function advance() {
    // 单选选完自动跳下一题；已是最后一题则跳 Submit
    setQIndex((i) => Math.min(i + 1, submitIdx));
    setOptIndex(0);
  }

  function buildReply(): string {
    const lines: string[] = [];
    questions.forEach((q, qi) => {
      const labels = picked[qi] ?? [];
      const other = (otherText[qi] ?? "").trim();
      const parts: string[] = [];
      if (labels.length) parts.push(labels.join("、"));
      if (other) parts.push(other);
      if (parts.length) {
        const key = q.header?.trim() || q.question.trim();
        lines.push(`${key}：${parts.join("；")}`);
      }
    });
    return lines.join("\n");
  }

  function doSubmit() {
    const reply = buildReply();
    onSubmit(reply || "（用户未明确选择，请按最合理方向继续）");
  }

  function onKeyDown(e: React.KeyboardEvent) {
    const target = e.target as HTMLElement;
    const inInput = target.tagName === "INPUT" || target.tagName === "TEXTAREA";
    if (e.key === "Escape") {
      e.preventDefault();
      onCancel();
      return;
    }
    if (inInput) return; // 输入框里让它自己处理，不抢方向键
    if (e.key === "ArrowLeft") {
      e.preventDefault();
      setQIndex((i) => Math.max(0, i - 1));
      setOptIndex(0);
    } else if (e.key === "ArrowRight") {
      e.preventDefault();
      setQIndex((i) => Math.min(submitIdx, i + 1));
      setOptIndex(0);
    } else if (qIndex === submitIdx) {
      if (e.key === "Enter") {
        e.preventDefault();
        doSubmit();
      }
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setOptIndex((i) => (i <= 0 ? rows.length - 1 : i - 1));
    } else if (e.key === "ArrowDown") {
      e.preventDefault();
      setOptIndex((i) => (i >= rows.length - 1 ? 0 : i + 1));
    } else if (e.key === "Enter") {
      e.preventDefault();
      chooseRow(optIndex);
    }
  }

  const answeredCount = questions.filter(
    (_, qi) => (picked[qi]?.length ?? 0) > 0 || (otherText[qi] ?? "").trim(),
  ).length;

  return (
    <div className="ask-card" ref={rootRef} tabIndex={0} onKeyDown={onKeyDown}>
      {/* 顶部标签：问题标题 … Submit，可左右切换 */}
      <div className="ask-tabs">
        <span className="ask-tabs-arrow">←</span>
        {questions.map((q, qi) => {
          const answered = (picked[qi]?.length ?? 0) > 0 || !!(otherText[qi] ?? "").trim();
          return (
            <button
              key={qi}
              className={`ask-tab${qi === qIndex ? " active" : ""}`}
              onClick={() => {
                setQIndex(qi);
                setOptIndex(0);
              }}
            >
              {answered ? "✓" : "□"} {q.header?.trim() || `问题 ${qi + 1}`}
            </button>
          );
        })}
        <button
          className={`ask-tab ask-tab-submit${qIndex === submitIdx ? " active" : ""}`}
          onClick={doSubmit}
        >
          ✔ 提交
        </button>
        <span className="ask-tabs-arrow">→</span>
      </div>

      {qIndex === submitIdx ? (
        <div className="ask-submit-body">
          <div className="ask-q-title">确认提交（已回答 {answeredCount}/{questions.length}）</div>
          <pre className="ask-preview">{buildReply() || "（还没选，可回去选，或直接提交让 AI 按最合理方向继续）"}</pre>
          <button className="ask-submit-btn" onClick={doSubmit}>
            提交回复 →
          </button>
        </div>
      ) : (
        <div className="ask-q-body">
          <div className="ask-q-title">{cur?.question}</div>
          {cur?.multiSelect && <div className="ask-q-hint">可多选</div>}
          <div className="ask-options">
            {rows.map((row, ri) => {
              const isPicked = !row.other && (picked[qIndex] ?? []).includes(row.label);
              const active = ri === optIndex;
              return (
                <div key={ri}>
                  <button
                    className={`ask-option${active ? " active" : ""}${isPicked ? " picked" : ""}`}
                    onMouseEnter={() => setOptIndex(ri)}
                    onClick={() => chooseRow(ri)}
                  >
                    <span className="ask-option-marker">{isPicked ? "◉" : row.other ? "＋" : "○"}</span>
                    <span className="ask-option-main">
                      <span className="ask-option-label">{row.label}</span>
                      {row.description && <span className="ask-option-desc">{row.description}</span>}
                    </span>
                  </button>
                  {row.other && otherOpen[qIndex] && (
                    <input
                      className="ask-other-input"
                      autoFocus
                      value={otherText[qIndex] ?? ""}
                      onChange={(e) => setOtherText((p) => ({ ...p, [qIndex]: e.target.value }))}
                      onKeyDown={(e) => {
                        if (e.key === "Enter" && !e.nativeEvent.isComposing) {
                          e.preventDefault();
                          advance();
                        }
                      }}
                      placeholder="自己填…Enter 确认"
                    />
                  )}
                </div>
              );
            })}
          </div>
        </div>
      )}
      <div className="ask-foot">
        Enter 选择 · ↑↓ 移动 · ←→ 换题 · Esc 取消
        <button className="ask-cancel" onClick={onCancel}>
          取消
        </button>
      </div>
    </div>
  );
}
