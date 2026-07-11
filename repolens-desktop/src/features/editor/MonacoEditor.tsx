import Editor, { DiffEditor, OnMount } from "@monaco-editor/react";
import { useEffect, useRef, useState } from "react";
import { useWorkbench } from "../../state/workbenchStore";
import { useClaudeStore } from "../../state/claudeStore";
import { fetchFileContent, languageFromPath, saveFileContent } from "../../api/fileApi";
import { acceptChange, rejectChange } from "../../api/agentReviewApi";
import { buildInjectionText } from "../claude/claudeInject";
import {
  getCachedContent,
  setCachedContent,
  getSavedVersion,
  setSavedVersion,
  registerMonaco,
  modelUriString,
} from "./models";

const IS_TAURI = "__TAURI_INTERNALS__" in window;

type Monaco = Parameters<OnMount>[1];
type IEditor = Parameters<OnMount>[0];

/** 读取指定分组的活动文件路径（用于 getState() 回调，避免闭包过期）。 */
const groupPath = (groupIndex: number) =>
  useWorkbench.getState().groups[groupIndex]?.activePath ?? null;

export function MonacoEditor({ groupIndex = 0 }: { groupIndex?: number }) {
  // 编辑器仅在已打开仓库时挂载（EditorArea 未打开仓库时渲染欢迎页），故 repoId 必非空。
  const repoId = useWorkbench((s) => s.repoId) as number;
  const path = useWorkbench((s) => s.groups[groupIndex]?.activePath ?? null);
  const revealTarget = useWorkbench((s) => s.revealTarget);
  const isActiveGroup = useWorkbench((s) => s.activeGroupIndex === groupIndex);
  const setDirty = useWorkbench((s) => s.setDirty);
  const markIndexStale = useWorkbench((s) => s.markIndexStale);
  const clearReveal = useWorkbench((s) => s.clearReveal);
  const setCursor = useWorkbench((s) => s.setCursor);

  // CC-3: external reload signal.
  const fileReloadNonce = useWorkbench(
    (s) => (path != null ? (s.fileReloadNonces[path] ?? 0) : 0),
  );
  const conflictPaths = useWorkbench((s) => s.conflictPaths);
  const clearConflictPath = useWorkbench((s) => s.clearConflictPath);
  const isConflict = path != null && conflictPaths.includes(path);

  // 实时改动高亮（agent realtimeDiff）：当前文件若有 pending change，用 DiffEditor inline 展示。
  const realtimeChange = useWorkbench((s) => (path != null ? s.realtimeChanges[path] : undefined));
  const clearRealtimeChange = useWorkbench((s) => s.clearRealtimeChange);
  const bumpFileReload = useWorkbench((s) => s.bumpFileReload);
  const [realtimeBusy, setRealtimeBusy] = useState(false);
  const [realtimeError, setRealtimeError] = useState<string | null>(null);

  // accept/reject：接内核 bridge 的 /api/repos/{repoId}/agent/changes 端点。
  //   accept → 影子区该文件合并回真目录（正式落地）；reject → 撤销影子区那处（真目录不动）。
  // 成功后清掉高亮；accept 还刷新编辑器让真目录落地后的内容立即可见。
  async function decideRealtime(decision: "accept" | "reject") {
    if (path == null || realtimeChange == null || realtimeBusy) return;
    const filePath = path;
    const sessionId = realtimeChange.sessionId;
    const applied = realtimeChange.after;
    setRealtimeBusy(true);
    setRealtimeError(null);
    try {
      if (decision === "accept") {
        await acceptChange(repoId, sessionId, filePath);
        setCachedContent(repoId, filePath, applied);
        clearRealtimeChange(filePath);
        bumpFileReload(filePath); // 真目录已更新 → 刷新编辑器显示落地内容
      } else {
        await rejectChange(repoId, sessionId, filePath);
        clearRealtimeChange(filePath);
      }
    } catch (e) {
      setRealtimeError(
        `${decision === "accept" ? "接受" : "拒绝"}失败：${e instanceof Error ? e.message : String(e)}`,
      );
    } finally {
      setRealtimeBusy(false);
    }
  }

  // CC-4: "Send to Claude" availability.
  const rightEngine = useWorkbench((s) => s.rightEngine);
  const activeRepoId = useClaudeStore((s) => s.activeRepoId);
  const activePtyId = useClaudeStore(
    (s) => (s.activeRepoId != null ? s.sessions[s.activeRepoId]?.ptyId : undefined),
  );
  const showSendToClaude =
    IS_TAURI && rightEngine === "claude" && activeRepoId != null && activePtyId != null;

  const editorRef = useRef<IEditor | null>(null);
  const monacoRef = useRef<Monaco | null>(null);
  const decoRef = useRef<string[]>([]);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [content, setContent] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [ready, setReady] = useState(false);

  // 加载当前文件内容（带缓存，未保存的编辑因 model 常驻不丢失）
  useEffect(() => {
    if (!path) {
      setContent(null);
      return;
    }
    const cached = getCachedContent(repoId, path);
    if (cached != null) {
      setContent(cached);
      return;
    }
    let ignore = false;
    const loadingPath = path; // 本次请求锁定的路径
    setContent(null);
    fetchFileContent(repoId, path)
      .then((c) => {
        // ignore 覆盖 effect 重跑；再对当前活动路径做一次校验，双保险确保
        // 旧路径解析出的内容不会写进已切换到新文件的编辑器。
        if (ignore || groupPath(groupIndex) !== loadingPath) return;
        setCachedContent(repoId, loadingPath, c);
        setContent(c);
      })
      .catch((e) => {
        if (ignore || groupPath(groupIndex) !== loadingPath) return;
        setContent(`// 加载失败: ${e?.message ?? e}`);
      });
    return () => {
      ignore = true;
    };
  }, [repoId, path, groupIndex]);

  // ── CC-3: External-reload effect ────────────────────────────
  // Fires when workbenchStore.bumpFileReload(path) is called after a
  // repo-file-changed event for this file.  The content cache has already
  // been cleared by ClaudeCodePanel; we fetch fresh content and push it
  // directly into the Monaco model so there's no flicker from a full remount.
  useEffect(() => {
    if (!path || !repoId || fileReloadNonce === 0) return;
    let cancelled = false;
    fetchFileContent(repoId, path)
      .then((newContent) => {
        if (cancelled) return;
        setCachedContent(repoId, path, newContent);
        const editor = editorRef.current;
        if (editor) {
          editor.setValue(newContent);
          const model = editor.getModel();
          if (model) setSavedVersion(repoId, path, model.getAlternativeVersionId());
          setDirty(path, false);
        } else {
          // Editor not yet mounted; the main load effect will pick up the
          // freshly-cached content on next mount.
          setContent(newContent);
        }
      })
      .catch((e) => {
        if (!cancelled) console.warn(`[CC-3] reload failed for ${path}:`, e);
      });
    return () => {
      cancelled = true;
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [repoId, path, fileReloadNonce]);

  // ── CC-4: Send to Claude ─────────────────────────────────────
  // Fix (stale-closure + engine guard): reads all live state at call time so
  // that the context-menu action (registered once on mount) always operates on
  // the currently active file / pty, not the mount-time snapshot.
  async function handleSendToClaude() {
    // Guard: no-op unless the Claude Code engine is active.
    if (useWorkbench.getState().rightEngine !== "claude") return;
    // Read live path (avoids stale mount-time `path` captured by the context-menu closure).
    const livePath = groupPath(groupIndex);
    // Read live ptyId (avoids stale mount-time `activePtyId`).
    const claudeState = useClaudeStore.getState();
    const livePtyId =
      claudeState.activeRepoId != null
        ? claudeState.sessions[claudeState.activeRepoId]?.ptyId
        : undefined;
    if (!livePath || livePtyId == null) return;
    const editor = editorRef.current;
    const sel = editor?.getSelection();
    const model = editor?.getModel();
    const selectedText =
      sel && !sel.isEmpty() && model ? model.getValueInRange(sel) : undefined;
    const text = buildInjectionText(livePath, selectedText);
    try {
      const { invoke } = await import("@tauri-apps/api/core");
      await invoke("term_write", { id: livePtyId, data: text });
    } catch (e) {
      console.warn("[CC-4] term_write failed:", e);
    }
  }

  async function save() {
    const editor = editorRef.current;
    const p = groupPath(groupIndex);
    const rid = useWorkbench.getState().repoId;
    if (!editor || !p || rid == null) return;
    const model = editor.getModel();
    if (!model) return;
    setSaveError(null);
    try {
      await saveFileContent(rid, p, model.getValue());
      setSavedVersion(rid, p, model.getAlternativeVersionId());
      setCachedContent(rid, p, model.getValue());
      useWorkbench.getState().setDirty(p, false);
      markIndexStale();
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : String(e));
    }
  }

  const onMount: OnMount = (editor, monaco) => {
    editorRef.current = editor;
    monacoRef.current = monaco;
    registerMonaco(monaco);
    setReady(true);
    editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () => {
      void save();
    });
    // CC-4: "发到 Claude" in the context menu / command palette.
    editor.addAction({
      id: "repolens.sendToClaude",
      label: "⚡ 发到 Claude",
      contextMenuGroupId: "navigation",
      contextMenuOrder: 1.5,
      run: () => void handleSendToClaude(),
    });
    editor.onDidChangeCursorPosition((e) => {
      if (useWorkbench.getState().activeGroupIndex === groupIndex) {
        setCursor({ line: e.position.lineNumber, col: e.position.column });
      }
    });
    editor.onDidChangeModelContent(() => {
      const p = groupPath(groupIndex);
      const rid = useWorkbench.getState().repoId;
      const model = editor.getModel();
      if (!p || !model || rid == null) return;
      if (getSavedVersion(rid, p) == null) setSavedVersion(rid, p, 1); // 初始 model 版本即已保存态
      setDirty(p, model.getAlternativeVersionId() !== getSavedVersion(rid, p));
    });
  };

  // 跳转到行：仅活动组的编辑器响应 revealTarget，model 就绪后 reveal + 高亮 2s
  useEffect(() => {
    if (!isActiveGroup) return;
    if (!revealTarget || revealTarget.path !== path || content == null) return;
    const editor = editorRef.current;
    const monaco = monacoRef.current;
    if (!editor || !monaco) return;
    const line = revealTarget.line;
    editor.revealLineInCenter(line);
    editor.setPosition({ lineNumber: line, column: 1 });
    // 清掉上一次的定时器与高亮，避免堆积
    if (timerRef.current) clearTimeout(timerRef.current);
    editor.deltaDecorations(decoRef.current, []);
    decoRef.current = editor.deltaDecorations(
      [],
      [
        {
          range: new monaco.Range(line, 1, line, 1),
          options: { isWholeLine: true, className: "reveal-line-highlight" },
        },
      ],
    );
    timerRef.current = setTimeout(() => {
      editor.deltaDecorations(decoRef.current, []);
      decoRef.current = [];
    }, 2000);
    clearReveal();
  }, [revealTarget, path, content, ready, clearReveal, isActiveGroup]);

  // 仅卸载时清理定时器
  useEffect(
    () => () => {
      if (timerRef.current) clearTimeout(timerRef.current);
    },
    [],
  );

  if (!path) {
    return (
      <div style={{ margin: "auto", color: "var(--vs-fg-dim)", textAlign: "center" }}>
        <div className="codicon codicon-files" style={{ fontSize: 48, opacity: 0.4 }} />
        <div style={{ marginTop: 8 }}>从资源管理器打开文件，或 ⌘P 快速打开</div>
      </div>
    );
  }
  // 有实时改动时用 before/after 渲染 diff，不依赖 fetch 的 content（新建文件真目录还没有）。
  if (content == null && !realtimeChange)
    return <div style={{ margin: "auto", color: "var(--vs-fg-dim)" }}>加载中…</div>;
  return (
    // Relative wrapper so the "Send to Claude" button can be absolutely positioned.
    <div style={{ flex: 1, position: "relative", overflow: "hidden", display: "flex", flexDirection: "column" }}>
      {saveError && <div className="save-error">保存失败：{saveError}</div>}
      {/* CC-3: Conflict banner — file changed on disk while editor has unsaved edits. */}
      {isConflict && (
        <div className="conflict-banner">
          ⚠ 此文件已被 Claude 修改，但编辑器中有未保存的改动。请手动处理冲突后保存。
          <button
            className="conflict-banner-dismiss"
            onClick={() => clearConflictPath(path)}
          >
            关闭
          </button>
        </div>
      )}
      {/* CC-4: Send to Claude toolbar button — visible when Claude Code engine is active. */}
      {showSendToClaude && (
        <button
          className="send-to-claude-btn"
          title="发到 Claude（无选区 → @文件，有选区 → 代码块）"
          onClick={() => void handleSendToClaude()}
        >
          ⚡ 发到 Claude
        </button>
      )}
      {realtimeChange ? (
        <>
          {/* 实时改动工具条：文件级 接受/拒绝（每处改动的按钮待后端 hunk 端点后细化） */}
          <div className="realtime-diff-bar">
            <span className="realtime-diff-title">
              ⚡ AI 实时改动 · {realtimeChange.changeType === "CREATE" ? "新建文件" : "修改文件"}
            </span>
            {realtimeError && <span className="realtime-diff-error">{realtimeError}</span>}
            <span className="realtime-diff-spacer" />
            <button
              className="realtime-btn realtime-accept"
              disabled={realtimeBusy}
              onClick={() => void decideRealtime("accept")}
            >
              ✓ 接受
            </button>
            <button
              className="realtime-btn realtime-reject"
              disabled={realtimeBusy}
              onClick={() => void decideRealtime("reject")}
            >
              ✕ 拒绝
            </button>
          </div>
          <DiffEditor
            height="100%"
            theme="vs-dark"
            language={languageFromPath(path)}
            original={realtimeChange.before}
            modified={realtimeChange.after}
            options={{
              renderSideBySide: false, // inline diff：绿增红删
              readOnly: true,
              minimap: { enabled: false },
              fontSize: 13,
              automaticLayout: true,
            }}
          />
        </>
      ) : (
        <Editor
          height="100%"
          theme="vs-dark"
          path={modelUriString(repoId, path)}
          defaultLanguage={languageFromPath(path)}
          defaultValue={content ?? ""}
          onMount={onMount}
          options={{ minimap: { enabled: true }, fontSize: 13, automaticLayout: true }}
        />
      )}
    </div>
  );
}
