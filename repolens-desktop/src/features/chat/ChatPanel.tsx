import { useCallback, useEffect, useRef, useState } from "react";
import { answerCodeQuestion, answerCodeQuestionStream } from "../../api/chatApi";
import { deleteSession, listSessions, loadMessages, renameSession } from "../../api/sessionApi";
import { fetchTree } from "../../api/treeApi";
import { searchSymbols } from "../../api/symbolApi";
import { fanoutSolutions, SolutionSetView } from "../../api/solutionApi";
import { answerAgentQuestion } from "../../api/agentReviewApi";
import { AskQuestion, CodeAnswer, CodeAnswerPayload, MentionChip } from "../../types/chat";
import { FileTreeNode } from "../../types/tree";
import { ChatSessionMeta, ThreadMessage } from "../../types/session";
import { useWorkbench } from "../../state/workbenchStore";
import { ReferenceCard } from "./ReferenceCard";
import { AgentTrace, stepSummary } from "./AgentTrace";
import { renderMarkdown } from "./ccMarkdown";
import { SolutionCards } from "./SolutionCards";
import { MemoryPanel } from "./MemoryPanel";
import { ChangesCard } from "./ChangesCard";
import { MentionMenu } from "./MentionMenu";
import { MentionItem, filterMentionItems, navigateMentionMenu, buildSymbolMentionValue, findAtTrigger } from "./mentionUtils";
import { SlashMenu } from "./SlashMenu";
import { AskCard } from "./AskCard";
import { SlashItem, findSlashTrigger, filterSlashItems } from "./slashUtils";
import { fetchSlashItems } from "../../api/slashApi";
import { useI18n } from "../../i18n/I18nProvider";
import type { TFn } from "../../i18n/I18nProvider";
import "./chat.css";
import "./change.css";

const QUICK = ["创建用户接口在哪里？", "用户查询的调用链是怎样的？", "UserService 有哪些方法？"];

/** 五档权限模式（对接内核 M4 PermissionMode）。Tab/Shift+Tab 在输入框里循环切换。 */
type PermMode = "DEFAULT" | "PLAN" | "ACCEPT_EDITS" | "AUTO" | "BYPASS";
const PERM_MODES: { v: PermMode; label: string; hint: string }[] = [
  { v: "DEFAULT", label: "Default", hint: "改动落影子区，逐处审批后合并" },
  { v: "PLAN", label: "Plan", hint: "只读规划，不改代码（只暴露只读工具）" },
  { v: "ACCEPT_EDITS", label: "Accept Edits", hint: "自动接受文件编辑，其余仍确认" },
  { v: "AUTO", label: "Auto", hint: "自动执行工具，少打断" },
  { v: "BYPASS", label: "Bypass", hint: "绕过全部确认（危险，仅可信仓库）" },
];

/** Flatten a FileTreeNode tree into an array of file paths (leaves only). */
function flattenTree(node: FileTreeNode): string[] {
  if (!node.directory) return [node.path];
  return (node.children ?? []).flatMap((c) => flattenTree(c));
}

export function ChatPanel() {
  const { t } = useI18n();
  const repoId = useWorkbench((s) => s.repoId);
  const activePath = useWorkbench((s) => s.activePath);
  const newChatNonce = useWorkbench((s) => s.newChatNonce);
  const focusChatNonce = useWorkbench((s) => s.focusChatNonce);
  const openFile = useWorkbench((s) => s.openFile);
  const setRealtimeChange = useWorkbench((s) => s.setRealtimeChange);
  const clearAllRealtimeChanges = useWorkbench((s) => s.clearAllRealtimeChanges);

  const [messages, setMessages] = useState<ThreadMessage[]>([]);
  const [sessionId, setSessionId] = useState<number | undefined>(undefined);
  const [question, setQuestion] = useState("");
  // topK 是老 RAG 检索参数，自研内核 agent 路径不用它；保留常量默认值仅为兼容 payload 契约（后端内核路径忽略）。
  const topK = 5;
  // 五档权限模式（已取代旧的问答/编码二分——PLAN 档=只读问答，其余档可自主改代码）：
  // Tab/Shift+Tab 循环切换。
  const [permMode, setPermMode] = useState<PermMode>("DEFAULT");
  // 实时改动高亮开关：开启后 agent 每改一个文件即在编辑器 inline diff。
  const [realtime, setRealtime] = useState(true);

  // 循环切换权限模式（next=Tab / prev=Shift+Tab）。
  const cyclePerm = useCallback((dir: "next" | "prev") => {
    setPermMode((cur) => {
      const i = PERM_MODES.findIndex((m) => m.v === cur);
      const n = (i + (dir === "next" ? 1 : PERM_MODES.length - 1)) % PERM_MODES.length;
      return PERM_MODES[n].v;
    });
  }, []);
  const [streaming, setStreaming] = useState(false);
  const [error, setError] = useState<string>();
  // askUser 反问：agent 挂起提问时置入，用户回复后清空（回复经 /agent/answer 回传唤醒 agent）。
  const [pendingAsk, setPendingAsk] = useState<AskQuestion | null>(null);

  const [overlay, setOverlay] = useState<"history" | "memory" | null>(null);
  const [sessions, setSessions] = useState<ChatSessionMeta[]>([]);
  const [sessionsLoading, setSessionsLoading] = useState(false);
  const [sessionsError, setSessionsError] = useState<string>();
  const [editingId, setEditingId] = useState<number | null>(null);
  const [editTitle, setEditTitle] = useState("");

  // ── @提及状态 ──────────────────────────────────────────────────────────────
  const [chips, setChips] = useState<MentionChip[]>([]);
  const [menuOpen, setMenuOpen] = useState(false);
  const [menuQuery, setMenuQuery] = useState('');
  const [fileItems, setFileItems] = useState<MentionItem[]>([]);
  const [symbolItems, setSymbolItems] = useState<MentionItem[]>([]);
  const [menuLoading, setMenuLoading] = useState(false);
  const [menuActiveIndex, setMenuActiveIndex] = useState(0);
  const [menuTriggerPos, setMenuTriggerPos] = useState(-1); // position of @ in question

  // ── / 斜杠命令面板状态 ──────────────────────────────────────────────────────
  const [slashOpen, setSlashOpen] = useState(false);
  const [slashQuery, setSlashQuery] = useState('');
  const [slashItems, setSlashItems] = useState<SlashItem[]>([]);
  const [slashActiveIndex, setSlashActiveIndex] = useState(0);
  // 选中的 skill：以蓝色 chip 显示在输入框里，发送时拼成 /skill-name <正文>。
  const [activeSkill, setActiveSkill] = useState<string | null>(null);

  // 拉取当前仓库的斜杠项（skill + 自定义命令）；换仓库重拉，失败降级为空。
  const slashFetchingRef = useRef(false);
  useEffect(() => {
    if (!repoId) {
      setSlashItems([]);
      return;
    }
    let cancelled = false;
    slashFetchingRef.current = true;
    void fetchSlashItems(repoId)
      .then((items) => {
        if (!cancelled) setSlashItems(items);
      })
      .finally(() => {
        slashFetchingRef.current = false;
      });
    return () => {
      cancelled = true;
    };
  }, [repoId]);

  // 自愈：打开面板时若列表为空（首拉恰逢后端重启失败等），重新拉一次。带并发保护，避免反复打。
  useEffect(() => {
    if (!slashOpen || !repoId || slashItems.length > 0 || slashFetchingRef.current) return;
    slashFetchingRef.current = true;
    void fetchSlashItems(repoId)
      .then((items) => setSlashItems(items))
      .finally(() => {
        slashFetchingRef.current = false;
      });
  }, [slashOpen, repoId, slashItems.length]);

  const threadRef = useRef<HTMLDivElement>(null);
  const endRef = useRef<HTMLDivElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  // 流式生命周期：当前流的 AbortController + 单调递增的「代际」计数。
  // 任何会切换/清空线程的操作都会 abort 旧流并 bump streamGen，使旧流的迟到回调
  // （onToken/onDone/非流式回退）因代际不符而被丢弃，杜绝跨会话串写。
  const streamAbortRef = useRef<AbortController | null>(null);
  const streamGenRef = useRef(0);

  // 中止在途流并推进代际：调用后所有旧流回调都会因 alive() 为假而失效。
  const abortStream = useCallback(() => {
    streamGenRef.current += 1;
    streamAbortRef.current?.abort();
    streamAbortRef.current = null;
    setStreaming(false);
    setPendingAsk(null);
  }, []);

  // 卸载时中止在途流（并推进代际），避免卸载后 setState 与串写。
  useEffect(
    () => () => {
      streamGenRef.current += 1;
      streamAbortRef.current?.abort();
      streamAbortRef.current = null;
    },
    [],
  );

  // 切换仓库：中止在途流，清空线程与会话，避免串仓库/旧流迟到写入。
  useEffect(() => {
    abortStream();
    setMessages([]);
    setSessionId(undefined);
    setError(undefined);
    setOverlay(null);
    setMenuOpen(false);
    setChips([]);
  }, [repoId, abortStream]);

  // 菜单打开时加载文件列表
  useEffect(() => {
    if (!menuOpen || !repoId) return;
    let ignore = false;
    setMenuLoading(true);
    fetchTree(repoId)
      .then((tree) => {
        if (ignore) return;
        const paths = flattenTree(tree);
        setFileItems(
          paths.map((p) => ({
            id: `file:${p}`,
            type: 'file' as const,
            label: p,
            value: p,
          })),
        );
        setMenuLoading(false);
      })
      .catch(() => {
        if (!ignore) setMenuLoading(false);
      });
    return () => {
      ignore = true;
    };
  }, [menuOpen, repoId]);

  // query >= 2 chars: search symbols
  useEffect(() => {
    if (!menuOpen || !repoId || menuQuery.length < 2) return;
    let ignore = false;
    searchSymbols(repoId, menuQuery)
      .then((hits) => {
        if (ignore) return;
        setSymbolItems(
          hits.map((h) => {
            // Display label uses '.' for readability; value uses '#' so the backend
            // can reliably parse ClassName#methodName without ambiguity.
            const label = h.methodName
              ? `${h.className ?? ''}.${h.methodName}`
              : (h.className ?? `symbol:${h.id}`);
            const value = buildSymbolMentionValue(h.className, h.methodName) || label;
            return {
              id: `symbol:${h.id}`,
              type: 'symbol' as const,
              label,
              value,
            };
          }),
        );
      })
      .catch(() => {});
    return () => {
      ignore = true;
    };
  }, [menuOpen, menuQuery, repoId]);

  // 新消息 / 流式增量 → 滚到底部。
  useEffect(() => {
    endRef.current?.scrollIntoView({ block: "end" });
  }, [messages]);

  // 命令面板 / 快捷方式请求「新建对话」。
  useEffect(() => {
    if (newChatNonce > 0) newChat();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [newChatNonce]);

  // ActivityBar 火花图标 / 命令请求聚焦输入框。
  useEffect(() => {
    if (focusChatNonce > 0) textareaRef.current?.focus();
  }, [focusChatNonce]);

  // 就地更新线程最后一条（正在生成的）助手消息。
  const patchLast = useCallback(
    (patch: Partial<ThreadMessage> | ((m: ThreadMessage) => ThreadMessage)) => {
      setMessages((prev) => {
        if (prev.length === 0) return prev;
        const copy = prev.slice();
        const last = copy[copy.length - 1];
        copy[copy.length - 1] = typeof patch === "function" ? patch(last) : { ...last, ...patch };
        return copy;
      });
    },
    [],
  );

  // 用权威结果（done / 非流式）覆盖当前助手消息。
  function applyAnswer(res: CodeAnswer) {
    patchLast((m) => ({
      ...m,
      content: res.answer || m.content,
      references: res.references ?? m.references,
      agentSteps: res.agentSteps ?? m.agentSteps,
      agentIterations: res.agentIterations,
      agentToolCalls: res.agentToolCalls,
      fileChanges: res.fileChanges,
      agentRunId: res.agentRunId,
      sessionId: res.sessionId,
      degraded: res.degraded,
      degradeReason: res.degradeReason,
      modelName: res.modelName,
      costMs: res.costMs,
      promptTokens: res.promptTokens,
      completionTokens: res.completionTokens,
      streaming: false,
    }));
    if (res.sessionId != null) setSessionId(res.sessionId);
    // 改动为「暂存提议」，未落盘：不在此刷新磁盘/文件树，交由审批卡在「应用」时处理。
  }

  async function submit() {
    const base = question.trim();
    // 有选中的 skill 时，拼成 /skill-name <补充需求>（后端 SkillSlashResolver 路由）。
    const q = activeSkill ? "/" + activeSkill + (base ? " " + base : "") : base;
    if (!q || streaming) return;
    if (!repoId) {
      setError("请先在左侧选择一个仓库");
      return;
    }
    setError(undefined);
    setPendingAsk(null);
    setActiveSkill(null);
    // 追加用户消息 + 占位助手消息（后续流式写入）。
    setMessages((prev) => [
      ...prev,
      { role: "USER", content: q },
      { role: "ASSISTANT", content: "", streaming: true },
    ]);
    setQuestion("");
    setStreaming(true);

    // 本次发送的代际与取消句柄。所有回调先过 alive() 门：一旦用户切换会话/仓库/
    // 新建对话/卸载，代际推进 → 旧流回调整体失效，绝不写入当前（已换）会话。
    const myGen = ++streamGenRef.current;
    const controller = new AbortController();
    streamAbortRef.current = controller;
    const alive = () => streamGenRef.current === myGen;

    const payload: CodeAnswerPayload = {
      question: q,
      topK,
      // 统一走 agent（mode 固定 code）；行为由五档 permissionMode 决定（PLAN=只读问答）。
      mode: "code",
      permissionMode: permMode,
      ...(realtime ? { realtimeDiff: true } : {}),
      ...(sessionId != null ? { sessionId } : {}),
      ...(chips.length > 0
        ? { mentions: chips.map((c) => ({ type: c.type, value: c.value, extra: c.extra })) }
        : {}),
    };
    setChips([]);
    // 新一轮 realtime：清掉上一轮遗留的实时改动高亮，避免旧 diff 串场。
    if (realtime) clearAllRealtimeChanges();

    let streamFailed = false;
    let gotAnything = false;
    try {
      try {
        await answerCodeQuestionStream(
          repoId,
          payload,
          {
            onMeta: (meta) => {
              if (!alive()) return;
              gotAnything = true;
              patchLast({ references: meta.references ?? [], modelName: meta.modelName });
            },
            onToken: (text) => {
              if (!alive()) return;
              gotAnything = true;
              patchLast((m) => ({ ...m, content: m.content + text }));
            },
            onStep: (step) => {
              // 流式 agent：单步完成，追加到轨迹列表（在途中显示）。
              if (!alive()) return;
              gotAnything = true;
              patchLast((m) => ({
                ...m,
                agentSteps: [...(m.agentSteps ?? []), step],
                agentToolCalls: (m.agentToolCalls ?? 0) + 1,
              }));
            },
            onSteps: (s) => {
              // 非流式回落：批量覆盖轨迹。
              if (!alive()) return;
              gotAnything = true;
              patchLast({
                agentSteps: s.agentSteps,
                agentIterations: s.agentIterations,
                agentToolCalls: s.agentToolCalls,
              });
            },
            onFileChange: (change) => {
              // 实时改动高亮：记录 before/after，并打开该文件让 inline diff 立即可见。
              if (!alive()) return;
              gotAnything = true;
              setRealtimeChange(change);
              openFile(change.filePath);
            },
            onAsk: (ask) => {
              // agent 反问：弹提问卡等用户回复（SSE 未断，回复后 agent 继续）。
              if (!alive()) return;
              gotAnything = true;
              setPendingAsk(ask);
            },
            onDone: (res) => {
              if (!alive()) return;
              gotAnything = true;
              applyAnswer(res);
            },
            onError: (msg) => {
              if (!alive()) return;
              // 未收到任何数据才算真正失败，可安全回退到非流式。
              if (!gotAnything) streamFailed = true;
              else patchLast({ error: msg, streaming: false });
            },
          },
          controller.signal,
        );
      } catch {
        if (alive() && !gotAnything) streamFailed = true;
      }

      if (streamFailed && alive()) {
        try {
          const res = await answerCodeQuestion(repoId, payload);
          if (alive()) applyAnswer(res);
        } catch (e: unknown) {
          if (alive()) patchLast({ error: e instanceof Error ? e.message : String(e), streaming: false });
        }
      }
    } finally {
      // 兜底：仅当本次仍是当前流时才收尾。服务端未发 done 就关流时，
      // 这里保证 streaming 标志与消息上的 ▍ 光标一定复位。
      if (alive()) {
        streamAbortRef.current = null;
        setStreaming(false);
        patchLast((m) => (m.streaming ? { ...m, streaming: false } : m));
      }
    }
  }

  // 多方案（M8 fanout）：与流式问答分开的同步长请求——后端并行跑 N 个 agent，各自独占影子区隔离，
  // 都不落真目录，跑完把方案组塞进这条助手消息渲染成卡组。选定其一才会合并回真目录（在 SolutionCards 里）。
  async function submitFanout() {
    if (streaming) return;
    if (!repoId) {
      setError("请先在左侧选择一个仓库");
      return;
    }
    const q = question.trim();
    if (!q) {
      // 之前空输入时按钮 disabled、点了没反应像摆设；现在给明确反馈。
      setError("先在下面输入你的需求，再点「多方案」——它会并行探索几个不同实现让你对比选用。");
      textareaRef.current?.focus();
      return;
    }
    setError(undefined);
    setMessages((prev) => [
      ...prev,
      { role: "USER", content: q },
      { role: "ASSISTANT", content: "正在并行探索多个方案…（各自独占影子区隔离运行，稍候片刻）", streaming: true },
    ]);
    setQuestion("");
    setStreaming(true);

    // 与流式同一套代际门：期间切换会话/仓库/新建对话 → 代际推进 → 迟到结果整体作废。
    const myGen = ++streamGenRef.current;
    const alive = () => streamGenRef.current === myGen;
    try {
      const set = await fanoutSolutions(repoId, q, sessionId);
      if (!alive()) return;
      if (set.sessionId != null) setSessionId(set.sessionId);
      patchLast((m) => ({
        ...m,
        content: "已并行产出多个方案，对比后选用其一（选定才会合并回真目录，其余作废）：",
        solutionSet: set,
        streaming: false,
      }));
    } catch (e: unknown) {
      if (!alive()) return;
      patchLast({ error: e instanceof Error ? e.message : String(e), streaming: false });
    } finally {
      if (alive()) setStreaming(false);
    }
  }

  // 方案组被选定后，把更新后的视图写回对应消息（选中卡定型、其余标作废）。
  const updateSolutionAt = useCallback((idx: number, set: SolutionSetView) => {
    setMessages((prev) => {
      if (idx < 0 || idx >= prev.length) return prev;
      const copy = prev.slice();
      copy[idx] = { ...copy[idx], solutionSet: set };
      return copy;
    });
  }, []);

  // 回传 askUser 反问的回复（来自多选卡片合成的 reply）：唤醒挂起的 agent 继续跑。
  async function answerAsk(reply: string) {
    const ask = pendingAsk;
    if (!ask || !repoId) return;
    setPendingAsk(null);
    // 把用户的选择作为一条用户消息显示在线程里（对话连贯）。
    if (reply.trim()) {
      setMessages((prev) => [...prev, { role: "USER", content: reply }]);
    }
    try {
      await answerAgentQuestion(repoId, ask.questionId, reply);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }

  function newChat() {
    if (streaming) return;
    abortStream();
    setMessages([]);
    setSessionId(undefined);
    setError(undefined);
    setOverlay(null);
    setTimeout(() => textareaRef.current?.focus(), 0);
  }

  async function openHistory() {
    if (repoId == null) return;
    setOverlay("history");
    setEditingId(null);
    setSessionsLoading(true);
    setSessionsError(undefined);
    try {
      setSessions(await listSessions(repoId));
    } catch (e: unknown) {
      setSessionsError(e instanceof Error ? e.message : String(e));
    } finally {
      setSessionsLoading(false);
    }
  }

  function beginRename(s: ChatSessionMeta, e: React.MouseEvent) {
    e.stopPropagation();
    setEditingId(s.id);
    setEditTitle(s.title || "");
  }

  async function commitRename(id: number) {
    if (repoId == null) return;
    const title = editTitle.trim();
    setEditingId(null);
    if (!title) return;
    const prev = sessions.find((x) => x.id === id)?.title ?? "";
    if (title === prev) return;
    // 乐观更新，失败回滚。
    setSessions((list) => list.map((x) => (x.id === id ? { ...x, title } : x)));
    try {
      await renameSession(repoId, id, title);
    } catch (err: unknown) {
      setSessions((list) => list.map((x) => (x.id === id ? { ...x, title: prev } : x)));
      setSessionsError(err instanceof Error ? err.message : String(err));
    }
  }

  async function selectSession(s: ChatSessionMeta) {
    if (repoId == null) return;
    // 切会话前中止在途流并推进代际：旧流迟到回调不会写进即将加载的会话。
    abortStream();
    const myGen = streamGenRef.current;
    try {
      const msgs = await loadMessages(repoId, s.id);
      // 加载期间又被切换/发送则放弃，避免旧会话内容覆盖新会话。
      if (streamGenRef.current !== myGen) return;
      setMessages(
        msgs.map((m) => ({ role: m.role, content: m.content, references: m.references ?? [] })),
      );
      setSessionId(s.id);
      setOverlay(null);
      setError(undefined);
    } catch (e: unknown) {
      if (streamGenRef.current !== myGen) return;
      setSessionsError(e instanceof Error ? e.message : String(e));
    }
  }

  async function removeSession(id: number, e: React.MouseEvent) {
    e.stopPropagation();
    if (repoId == null) return;
    // 删除的是当前会话则中止在途流，避免旧流写进已清空的线程。
    if (sessionId === id) abortStream();
    try {
      await deleteSession(repoId, id);
      setSessions((prev) => prev.filter((x) => x.id !== id));
      if (sessionId === id) {
        setMessages([]);
        setSessionId(undefined);
      }
    } catch (err: unknown) {
      setSessionsError(err instanceof Error ? err.message : String(err));
    }
  }

  // ── @提及：计算当前菜单项（fixed + file + symbol） ──────────────────────────
  const currentFileItem: MentionItem = {
    id: 'current-file',
    type: 'current-file',
    label: activePath ? activePath.split('/').pop() ?? activePath : '当前文件',
    value: activePath ?? '',
    disabled: !activePath,
  };

  const allBaseItems: MentionItem[] = [currentFileItem, ...fileItems, ...symbolItems];
  const filteredMenuItems = filterMentionItems(menuQuery, allBaseItems);

  const filteredSlashItems = filterSlashItems(slashQuery, slashItems);

  // Clamp slash active index when the filtered list shrinks.
  useEffect(() => {
    if (filteredSlashItems.length === 0) {
      setSlashActiveIndex(0);
    } else if (slashActiveIndex >= filteredSlashItems.length) {
      setSlashActiveIndex(filteredSlashItems.length - 1);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filteredSlashItems.length]);

  /** 选中一个斜杠项：设为"当前 skill"（输入框里蓝色 chip 显示），清掉已输入的 `/query`，光标回到输入框继续打补充需求。 */
  function selectSlash(item: SlashItem) {
    setActiveSkill(item.name);
    setQuestion('');
    setSlashOpen(false);
    setSlashQuery('');
    setTimeout(() => textareaRef.current?.focus(), 0);
  }

  // Clamp menuActiveIndex when the filtered list shrinks so the selection never
  // points past the end of the visible list (e.g. user narrows the query).
  useEffect(() => {
    if (filteredMenuItems.length === 0) {
      setMenuActiveIndex(0);
    } else if (menuActiveIndex >= filteredMenuItems.length) {
      setMenuActiveIndex(filteredMenuItems.length - 1);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filteredMenuItems.length]);

  function selectMention(item: MentionItem) {
    if (item.disabled) return;
    if (chips.length >= 5) {
      setMenuOpen(false);
      return;
    }
    const chip: MentionChip = {
      type: item.type === 'current-file' ? 'file' : (item.type as 'file' | 'symbol'),
      label: item.label,
      value: item.value,
    };
    setChips((prev) => [...prev, chip]);

    // Replace @query with @label + space
    const atPos = menuTriggerPos;
    const newQuestion =
      question.slice(0, atPos) +
      `@${item.label} ` +
      question.slice(atPos + 1 + menuQuery.length);
    setQuestion(newQuestion);
    setMenuOpen(false);
    setMenuQuery('');
    setSymbolItems([]);
    setTimeout(() => textareaRef.current?.focus(), 0);
  }

  function handleTextareaChange(e: React.ChangeEvent<HTMLTextAreaElement>) {
    const val = e.target.value;
    setQuestion(val);

    const cursor = e.target.selectionStart ?? val.length;
    // findAtTrigger enforces word-boundary: @ must be at start-of-input or after
    // whitespace, so emails like "a@b" never open the menu.
    const trigger = findAtTrigger(val, cursor);
    if (trigger) {
      if (!menuOpen) {
        setMenuOpen(true);
        setMenuActiveIndex(0);
        setSymbolItems([]);
      }
      setMenuTriggerPos(trigger.atIdx);
      setMenuQuery(trigger.query);
      // @ 与 / 互斥：@ 触发时确保斜杠面板关闭
      if (slashOpen) setSlashOpen(false);
      return;
    }
    // No active @ trigger
    if (menuOpen) {
      setMenuOpen(false);
      setMenuQuery('');
    }

    // / 斜杠触发：仅当输入以 / 开头且还在打命令名（无空格）时开面板
    const slashTrig = findSlashTrigger(val, cursor);
    if (slashTrig) {
      if (!slashOpen) {
        setSlashOpen(true);
        setSlashActiveIndex(0);
      }
      setSlashQuery(slashTrig.query);
      return;
    }
    if (slashOpen) {
      setSlashOpen(false);
      setSlashQuery('');
    }
  }

  function onKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (menuOpen) {
      // IME composition guard: ignore navigation/confirm keys while composing
      // (isComposing covers modern browsers; keyCode 229 covers legacy browsers).
      if (e.nativeEvent.isComposing || e.nativeEvent.keyCode === 229) return;
      if (e.key === 'ArrowUp') {
        e.preventDefault();
        setMenuActiveIndex((i) => navigateMentionMenu(i, 'up', filteredMenuItems.length));
        return;
      }
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setMenuActiveIndex((i) => navigateMentionMenu(i, 'down', filteredMenuItems.length));
        return;
      }
      if (e.key === 'Enter' || e.key === 'Tab') {
        e.preventDefault();
        const item = filteredMenuItems[menuActiveIndex];
        if (item) selectMention(item);
        return;
      }
      if (e.key === 'Escape') {
        e.preventDefault();
        setMenuOpen(false);
        return;
      }
    }
    // / 斜杠面板打开时：上下选、Enter/Tab 确认、Esc 关（优先于下面的 Tab 切档 / Enter 发送）。
    if (slashOpen) {
      if (e.nativeEvent.isComposing || e.nativeEvent.keyCode === 229) return;
      if (e.key === 'ArrowUp') {
        e.preventDefault();
        setSlashActiveIndex((i) => navigateMentionMenu(i, 'up', filteredSlashItems.length));
        return;
      }
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setSlashActiveIndex((i) => navigateMentionMenu(i, 'down', filteredSlashItems.length));
        return;
      }
      if (e.key === 'Enter' || e.key === 'Tab') {
        e.preventDefault();
        const item = filteredSlashItems[slashActiveIndex];
        if (item) selectSlash(item);
        return;
      }
      if (e.key === 'Escape') {
        e.preventDefault();
        setSlashOpen(false);
        return;
      }
    }
    // Tab / Shift+Tab 循环切换五档权限模式（菜单已关、非输入法组合时）。
    if (e.key === "Tab" && !e.nativeEvent.isComposing) {
      e.preventDefault();
      cyclePerm(e.shiftKey ? "prev" : "next");
      return;
    }
    // Enter 发送、Shift+Enter 换行——但输入法组合中（打中文选字按的回车）绝不发送，
    // 这治「没打完字就被发出去」：isComposing 覆盖现代浏览器，keyCode 229 兜底老引擎。
    if (
      e.key === "Enter" &&
      !e.shiftKey &&
      !e.nativeEvent.isComposing &&
      e.nativeEvent.keyCode !== 229
    ) {
      e.preventDefault();
      void submit();
    }
  }

  return (
    <div className="chat">
      <div className="chat-header">
        <span className="chat-title">{t("chat.title", "AI 会话")}</span>
        <div className="chat-header-actions">
          <button className="chat-icon-btn" title={t("chat.newChat", "新对话")} onClick={newChat} disabled={streaming || !repoId}>
            <span className="codicon codicon-add" />
            <span className="chat-icon-label">{t("chat.newChat", "新对话")}</span>
          </button>
          <button className="chat-icon-btn" title={t("chat.history", "历史会话")} onClick={() => void openHistory()} disabled={!repoId}>
            <span className="codicon codicon-history" />
          </button>
          <button
            className="chat-icon-btn"
            title={t("chat.memory", "长期记忆")}
            onClick={() => setOverlay(overlay === "memory" ? null : "memory")}
            disabled={!repoId}
          >
            <span className="codicon codicon-database" />
          </button>
          <span className="chat-privacy" title={t("chat.localBadgeTip", "所有推理走本地/内网，代码不出网")}>
            {t("chat.localBadge", "🔒 本地")}
          </span>
        </div>
      </div>

      <div className="chat-thread" ref={threadRef}>
        {error && <div className="chat-toplevel-error">{error}</div>}
        {!repoId ? (
          <NoRepoState t={t} />
        ) : messages.length === 0 ? (
          <EmptyState
            t={t}
            onPick={(q) => {
              setQuestion(q);
              textareaRef.current?.focus();
            }}
          />
        ) : (
          messages.map((m, i) =>
            m.role === "USER" ? (
              <UserMessage key={i} content={m.content} />
            ) : (
              <AssistantMessage
                key={i}
                repoId={repoId}
                msg={m}
                onSolutionUpdated={(set) => updateSolutionAt(i, set)}
              />
            ),
          )
        )}
        <div ref={endRef} />
      </div>

      {pendingAsk && (
        <AskCard
          ask={pendingAsk}
          onSubmit={(reply) => void answerAsk(reply)}
          onCancel={() => void answerAsk("")}
        />
      )}

      <div className="chat-input-area">
        <div className="chat-controls">
          <button
            type="button"
            className={`perm-pill perm-${permMode.toLowerCase()}`}
            onClick={() => cyclePerm("next")}
            title={`权限模式：${PERM_MODES.find((m) => m.v === permMode)?.hint ?? ""}（Tab 切换 / Shift+Tab 反向）`}
          >
            <span className="perm-pill-key">⇥</span>
            <span className="perm-pill-label">
              {PERM_MODES.find((m) => m.v === permMode)?.label ?? permMode}
            </span>
          </button>
          <button
            type="button"
            className="chat-fanout"
            onClick={() => void submitFanout()}
            disabled={streaming || !repoId}
            title="并行探索多个实现方案：先在下面输入需求，再点这里——后端并行跑 N 个 agent（各占独立影子区），产出真实指标对比，选定其一才合并回真目录"
          >
            <span className="codicon codicon-git-branch" /> {t("chat.multiSolution", "多方案")}
          </button>
          <label className="chat-realtime" title="开启后，AI 每改一个文件即在编辑器实时高亮 diff（绿增红删）">
            <input
              type="checkbox"
              checked={realtime}
              onChange={(e) => setRealtime(e.target.checked)}
            />
            {t("chat.realtime", "实时")}
          </label>
        </div>
        <div className="chat-inputbox" style={{ position: 'relative' }}>
          {menuOpen && (
            <MentionMenu
              items={filteredMenuItems}
              activeIndex={menuActiveIndex}
              onSelect={selectMention}
              onClose={() => setMenuOpen(false)}
              loading={menuLoading}
            />
          )}
          {slashOpen && (
            <SlashMenu
              items={filteredSlashItems}
              activeIndex={slashActiveIndex}
              onSelect={selectSlash}
              onClose={() => setSlashOpen(false)}
            />
          )}
          {(chips.length > 0 || activeSkill) && (
            <div className="mention-chips">
              {activeSkill && (
                <span className="skill-chip" title="当前 skill（发送时以 /skill 触发）">
                  /{activeSkill}
                  <button
                    className="skill-chip-del"
                    onClick={() => setActiveSkill(null)}
                    title="移除 skill"
                  >
                    ×
                  </button>
                </span>
              )}
              {chips.map((c, i) => (
                <span key={i} className="mention-chip">
                  @{c.label}
                  <button
                    className="mention-chip-del"
                    onClick={() => setChips((prev) => prev.filter((_, j) => j !== i))}
                  >
                    ×
                  </button>
                </span>
              ))}
            </div>
          )}
          <textarea
            ref={textareaRef}
            rows={3}
            value={question}
            onChange={handleTextareaChange}
            onKeyDown={onKeyDown}
            disabled={!repoId}
            placeholder={
              !repoId
                ? t("chat.placeholderNoRepo", "请先导入并选择一个仓库")
                : activeSkill
                ? `给 /${activeSkill} 补充需求（可留空直接发）…Enter 发送`
                : permMode === "PLAN"
                ? "只读问答：问一个关于代码的问题…Enter 发送，@ 引用文件，/ 唤起 skill"
                : "描述你的需求，AI 会自主读代码、改、验证直到完成…Enter 发送，Tab 切权限档，@ 引用文件，/ 唤起 skill"
            }
          />
          <button
            className="chat-send"
            onClick={() => void submit()}
            disabled={streaming || !repoId || (!question.trim() && !activeSkill)}
          >
            {streaming ? t("chat.thinking", "思考中…") : t("chat.send", "发送")}
          </button>
        </div>
      </div>

      {overlay === "history" && (
        <Overlay t={t} title={t("chat.history", "历史会话")} onClose={() => setOverlay(null)}>
          {sessionsLoading && <div className="chat-overlay-empty">{t("common.loading", "加载中…")}</div>}
          {sessionsError && <div className="chat-toplevel-error">{sessionsError}</div>}
          {!sessionsLoading && !sessionsError && sessions.length === 0 && (
            <div className="chat-overlay-empty">{t("chat.emptyHistory", "还没有历史会话——开始一段对话吧")}</div>
          )}
          {sessions.map((s) => (
            <div
              key={s.id}
              className={`chat-session-row ${sessionId === s.id ? "active" : ""}`}
              role="button"
              tabIndex={0}
              onClick={() => void selectSession(s)}
              onKeyDown={(e) => {
                if (e.key === "Enter") void selectSession(s);
              }}
            >
              <div className="chat-session-main">
                {editingId === s.id ? (
                  <input
                    className="chat-session-edit"
                    autoFocus
                    value={editTitle}
                    onChange={(e) => setEditTitle(e.target.value)}
                    onClick={(e) => e.stopPropagation()}
                    onBlur={() => void commitRename(s.id)}
                    onKeyDown={(e) => {
                      e.stopPropagation();
                      if (e.key === "Enter") {
                        e.preventDefault();
                        void commitRename(s.id);
                      } else if (e.key === "Escape") {
                        e.preventDefault();
                        setEditingId(null);
                      }
                    }}
                  />
                ) : (
                  <div
                    className="chat-session-title"
                    title="双击重命名"
                    onDoubleClick={(e) => beginRename(s, e)}
                  >
                    {s.title || t("chat.untitledSession", "未命名会话")}
                  </div>
                )}
                <div className="chat-session-preview">{s.lastMessagePreview || "（空会话）"}</div>
              </div>
              <span className="chat-session-count">{s.messageCount}</span>
              <button
                className="chat-session-del"
                title="重命名会话"
                onClick={(e) => beginRename(s, e)}
              >
                <span className="codicon codicon-edit" />
              </button>
              <button
                className="chat-session-del"
                title="删除会话"
                onClick={(e) => void removeSession(s.id, e)}
              >
                <span className="codicon codicon-trash" />
              </button>
            </div>
          ))}
        </Overlay>
      )}

      {overlay === "memory" && (
        <Overlay t={t} title={t("chat.memory", "长期记忆")} onClose={() => setOverlay(null)}>
          <MemoryPanel />
        </Overlay>
      )}
    </div>
  );
}

function NoRepoState({ t }: { t: TFn }) {
  return (
    <div className="chat-empty">
      <div className="chat-empty-icon">
        <span className="codicon codicon-sparkle" />
      </div>
      <div className="chat-empty-title">{t("chat.noRepoTitle", "未打开仓库")}</div>
      <div className="chat-empty-sub">{t("chat.noRepoSub", "导入一个仓库后即可开始 AI 会话")}</div>
    </div>
  );
}

function EmptyState({ t, onPick }: { t: TFn; onPick: (q: string) => void }) {
  // Claude Code 风欢迎框：带边框的终端 banner + tips + 快捷提问。
  return (
    <div className="cc-welcome">
      <div className="cc-welcome-box">
        <div className="cc-welcome-title">{t("chat.welcomeTitle", "✳ RepoLens Agent")}</div>
        <div className="cc-welcome-sub">{t("chat.welcomeSub", "自研 AI 内核 · 直接在你打开的文件夹里改 · git 可回溯")}</div>
      </div>
      <div className="cc-welcome-tips">
        <div className="cc-tip">· 描述需求，切到 <b>Auto</b> 档就全自动读代码 / 改 / 验证直到完成</div>
        <div className="cc-tip">· <b>Tab</b> 切换权限档（Default / Plan / Accept Edits / Auto / Bypass）</div>
        <div className="cc-tip">· <b>@</b> 引用文件 · <b>/</b> 唤起 skill · <b>Enter</b> 发送 · 需求含糊时我会先反问</div>
      </div>
      <div className="cc-welcome-chips">
        {QUICK.map((q) => (
          <button key={q} className="cc-chip" onClick={() => onPick(q)}>
            {q}
          </button>
        ))}
      </div>
    </div>
  );
}

function UserMessage({ content }: { content: string }) {
  // Claude Code 风：用户输入以 "> " 提示符回显（终端感）。
  return (
    <div className="cc-user">
      <span className="cc-user-caret">&gt;</span>
      <span className="cc-user-text">{content}</span>
    </div>
  );
}

function AssistantMessage({ repoId, msg, onSolutionUpdated }: {
  repoId: number;
  msg: ThreadMessage;
  onSolutionUpdated: (set: SolutionSetView) => void;
}) {
  const openAgentRun = useWorkbench((s) => s.openAgentRun);
  // Claude Code / Codex 风：轨迹默认收起，执行中只在正文区显示一行当前动作；用户想看细节再展开。
  const [open, setOpen] = useState<Set<string>>(() => new Set<string>());
  // 执行中的实时耗时计时器（对齐 Claude Code 的 "Working… 12s"）。
  const [elapsed, setElapsed] = useState(0);
  useEffect(() => {
    if (!msg.streaming) return;
    const start = Date.now();
    setElapsed(0);
    const id = setInterval(() => setElapsed(Math.floor((Date.now() - start) / 1000)), 1000);
    return () => clearInterval(id);
  }, [msg.streaming]);
  const toggle = (k: string) =>
    setOpen((prev) => {
      const n = new Set(prev);
      if (n.has(k)) n.delete(k);
      else n.add(k);
      return n;
    });

  const refs = msg.references ?? [];
  const steps = msg.agentSteps ?? [];
  const changes = msg.fileChanges ?? [];
  const empty = !msg.content && !msg.streaming && !msg.error;

  return (
    <div className="msg msg-assistant">
      <div className="msg-avatar">AI</div>
      <div className="msg-body">
        {msg.degraded && (
          <div className="msg-degrade">
            降级回答{msg.degradeReason ? `：${msg.degradeReason}` : ""}
          </div>
        )}

        {/* Claude Code 风：执行流（叙事 + 折叠工具批次）在最终总结之前，边跑边出现 */}
        {steps.length > 0 && <AgentTrace steps={msg.agentSteps} streaming={msg.streaming} />}

        {/* 执行中的实时进度行：当前动作 · 第 N 步 · 耗时 */}
        {msg.streaming && (
          <div className="msg-activity">
            <span className="msg-activity-spin">⟳</span>
            <span>{steps.length > 0 ? stepSummary(steps[steps.length - 1]) : "思考中"}…</span>
            {steps.length > 0 && <span className="msg-activity-count">第 {steps.length} 步</span>}
            <span className="msg-activity-count">{elapsed}s</span>
          </div>
        )}

        {/* 最终答复（大总结），排在执行流之后 */}
        {msg.content && (
          <div className="msg-text cc-md">
            {renderMarkdown(msg.content)}
            {msg.streaming && <span className="chat-cursor">▍</span>}
          </div>
        )}
        {empty && (
          <div className="msg-dim">（本次执行未生成文字总结，可展开上方批次查看做了什么）</div>
        )}
        {msg.error && <div className="chat-toplevel-error">{msg.error}</div>}

        {changes.length > 0 && (
          <ChangesCard repoId={repoId} sessionId={msg.sessionId} changes={changes} />
        )}

        {msg.solutionSet && (
          <SolutionCards repoId={repoId} set={msg.solutionSet} onSelected={onSolutionUpdated} />
        )}

        {refs.length > 0 && (
          <div className="msg-sections">
            <Section k="refs" title="引用证据" count={refs.length}
              open={open.has("refs")} onToggle={toggle}>
              {refs.map((r, i) => (
                <ReferenceCard key={`${r.filePath}:${r.startLine}:${r.chunkType ?? i}`}
                  reference={r} index={i} />
              ))}
            </Section>
          </div>
        )}

        {/* 计量：完成后 Claude Code 风页脚「✳ Worked for Xs · N tokens」（斜体灰） */}
        {!msg.streaming && (msg.costMs != null || msg.completionTokens != null) && (
          <div className="cc-footer">
            <span className="cc-footer-star">✳</span>
            {msg.costMs != null && <span>用时 {fmtDuration(msg.costMs)}</span>}
            {msg.completionTokens != null && <span>· {msg.completionTokens} tokens</span>}
            {msg.agentRunId != null && (
              <button className="chat-meta-link" onClick={() => openAgentRun(msg.agentRunId!)}>
                · 执行轨迹
              </button>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

function Section({
  k,
  title,
  count,
  open,
  onToggle,
  children,
}: {
  k: string;
  title: string;
  count: number;
  open: boolean;
  onToggle: (k: string) => void;
  children: React.ReactNode;
}) {
  return (
    <div className="msg-section">
      <button className="msg-section-head" onClick={() => onToggle(k)}>
        <span className={`codicon ${open ? "codicon-chevron-down" : "codicon-chevron-right"}`} />
        <span className="msg-section-title">{title}</span>
        <span className="msg-section-count">{count}</span>
      </button>
      {open && <div className="msg-section-body">{children}</div>}
    </div>
  );
}

function Overlay({
  t,
  title,
  onClose,
  children,
}: {
  t: TFn;
  title: string;
  onClose: () => void;
  children: React.ReactNode;
}) {
  return (
    <div className="chat-overlay">
      <div className="chat-overlay-head">
        <span>{title}</span>
        <button className="chat-icon-btn" onClick={onClose} title={t("common.close", "关闭")}>
          <span className="codicon codicon-close" />
        </button>
      </div>
      <div className="chat-overlay-body">{children}</div>
    </div>
  );
}

/** 人类可读时长：Claude Code 风（ms→s→m s）。 */
function fmtDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  const s = ms / 1000;
  if (s < 60) return `${s.toFixed(1)}s`;
  const m = Math.floor(s / 60);
  return `${m}m ${Math.round(s % 60)}s`;
}
