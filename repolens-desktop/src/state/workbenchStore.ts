import { create } from "zustand";
import type { FileChange } from "../types/chat";

export interface Tab {
  path: string;
  dirty: boolean;
}

export interface EditorGroup {
  tabs: Tab[];
  activePath: string | null;
}

interface WorkbenchState {
  /** 当前仓库 id；无仓库（冷启动 / 未导入）时为 null。 */
  repoId: number | null;
  view: "editor" | "graph" | "requirements" | "agentruns" | "settings" | "debt" | "provenance" | "traceability" | "adr" | "sensitive" | "mission" | "timeline" | "branch" | "drift" | "egress";
  sidebarMode: "explorer" | "search";
  /** Agent 执行视图当前选中的 run；从聊天「查看执行轨迹」跳转时设置。 */
  activeAgentRunId?: number;
  /**
   * CC-6: Requirement insight view target.  When set, RequirementGraphView automatically
   * opens the insight card for this requirement ID.  Cleared after consumption.
   */
  activeRequirementId?: number;
  /** 编辑器分组（VSCode 式分窗口）。默认单组；最多 2 组。 */
  groups: EditorGroup[];
  activeGroupIndex: number;
  /** 向后兼容的派生字段：镜像当前活动组的 tabs / activePath。 */
  tabs: Tab[];
  activePath: string | null;
  revealTarget: { path: string; line: number; nonce: number } | null;
  terminalVisible: boolean;
  indexStale: boolean;
  cursor: { line: number; col: number } | null;
  /** 文件树刷新触发器：递增时 FileTree 重新拉取。 */
  treeRefreshNonce: number;
  /** 新建对话触发器：命令面板 / 快捷方式递增，ChatPanel 监听后清空线程。 */
  newChatNonce: number;
  /** 导入文件夹触发器：命令面板 / 欢迎页递增，侧栏 RepoPicker 监听后弹出选择框。 */
  importFolderNonce: number;
  /** 从 Git URL 导入触发器：欢迎页递增，侧栏 RepoPicker 监听后打开 Git 表单。 */
  gitImportNonce: number;
  /** 聚焦 AI 会话触发器：ActivityBar 火花图标递增，ChatPanel 聚焦输入框。 */
  focusChatNonce: number;
  /** 保存当前文件触发器：命令面板递增，活动编辑器监听后保存。 */
  saveNonce: number;
  /** 右侧面板引擎："chat" = 自研 AI 会话（默认），"claude" = Claude Code TUI。 */
  rightEngine: "chat" | "claude";
  /**
   * CC-3: Per-path reload nonce.  When bumped for a path that is currently
   * open in MonacoEditor, the editor re-fetches the file content from disk.
   * Callers must also clear the content cache (disposeModelFor) before bumping.
   */
  fileReloadNonces: Record<string, number>;
  /**
   * CC-3: Paths of open-dirty files that were changed externally while the
   * editor has unsaved edits.  Shown as a conflict banner in MonacoEditor.
   */
  conflictPaths: string[];
  /**
   * 实时改动高亮（agent realtimeDiff）：filePath → 最新一次 FileChange（before/after 全文）。
   * 同一 filePath 覆盖更新；MonacoEditor 据此对打开的文件渲染 inline diff。
   */
  realtimeChanges: Record<string, FileChange>;
  setRealtimeChange: (change: FileChange) => void;
  clearRealtimeChange: (filePath: string) => void;
  clearAllRealtimeChanges: () => void;
  setRepoId: (id: number | null) => void;
  openFile: (path: string, line?: number) => void;
  closeTab: (path: string, groupIndex?: number) => void;
  setActive: (path: string, groupIndex?: number) => void;
  setDirty: (path: string, dirty: boolean) => void;
  setActiveGroup: (index: number) => void;
  splitEditor: () => void;
  closeGroup: (index: number) => void;
  /** K: 当前活动 chat session id；ChatPanel 在 sessionId 变化时写入，供 BranchGraphView 预填。 */
  activeSessionId: number | null;
  setActiveSessionId: (id: number | null) => void;
  setView: (v: "editor" | "graph" | "requirements" | "agentruns" | "settings" | "debt" | "provenance" | "traceability" | "adr" | "sensitive" | "mission" | "timeline" | "branch" | "drift" | "egress") => void;
  openAgentRun: (runId: number) => void;
  /**
   * CC-6: Navigate to the requirements view and open the insight card for the
   * given requirement ID.  Used by the external-changes → insight loop and by
   * the MCP show_requirement_viz UI-action tool.
   */
  openRequirementInsight: (reqId: number) => void;
  /** CC-6: Clear the activeRequirementId after it has been consumed. */
  clearActiveRequirementId: () => void;
  setSidebarMode: (m: "explorer" | "search") => void;
  toggleTerminal: () => void;
  markIndexStale: () => void;
  clearIndexStale: () => void;
  refreshTree: () => void;
  requestNewChat: () => void;
  requestImportFolder: () => void;
  requestGitImport: () => void;
  focusChat: () => void;
  requestSave: () => void;
  clearReveal: () => void;
  setCursor: (c: { line: number; col: number } | null) => void;
  setRightEngine: (engine: "chat" | "claude") => void;
  /** CC-3: Signal MonacoEditor to reload a specific file from disk. */
  bumpFileReload: (path: string) => void;
  /** CC-3: Record externally-changed paths that have unsaved local edits. */
  setConflictPaths: (paths: string[]) => void;
  /** CC-3: Dismiss a single conflict banner once the user acknowledges it. */
  clearConflictPath: (path: string) => void;
  /**
   * C3: Whether the middle editor Panel is collapsed (hidden) to give more room
   * to the terminal / Claude panel.  Persisted to localStorage so the preference
   * survives reloads.  openFile() auto-expands the editor when a file is opened.
   */
  editorCollapsed: boolean;
  /** C3: Toggle the editor collapsed state. */
  setEditorCollapsed: (collapsed: boolean) => void;
}

const initialRepoId = ((): number | null => {
  if (typeof localStorage === "undefined") return null;
  const raw = localStorage.getItem("repolens.repoId");
  if (raw == null || raw === "") return null;
  const n = Number(raw);
  return Number.isFinite(n) && n > 0 ? n : null;
})();

/** 把 groups / activeGroupIndex 与派生的顶层 tabs / activePath 保持同步。 */
function mirror(groups: EditorGroup[], activeGroupIndex: number) {
  const idx = Math.min(Math.max(activeGroupIndex, 0), groups.length - 1);
  const g = groups[idx] ?? { tabs: [], activePath: null };
  return { groups, activeGroupIndex: idx, tabs: g.tabs, activePath: g.activePath };
}

const emptyGroups = (): EditorGroup[] => [{ tabs: [], activePath: null }];

export const useWorkbench = create<WorkbenchState>((set) => ({
  repoId: initialRepoId,
  view: "editor",
  sidebarMode: "explorer",
  groups: emptyGroups(),
  activeGroupIndex: 0,
  tabs: [],
  activePath: null,
  revealTarget: null,
  terminalVisible: false,
  indexStale: false,
  cursor: null,
  treeRefreshNonce: 0,
  newChatNonce: 0,
  importFolderNonce: 0,
  gitImportNonce: 0,
  focusChatNonce: 0,
  saveNonce: 0,
  rightEngine: (() => {
    if (typeof localStorage === "undefined") return "chat";
    const v = localStorage.getItem("repolens.rightEngine");
    return (v === "claude" ? "claude" : "chat") as "chat" | "claude";
  })(),
  fileReloadNonces: {},
  conflictPaths: [],
  realtimeChanges: {},
  setRealtimeChange: (change) =>
    set((s) => ({ realtimeChanges: { ...s.realtimeChanges, [change.filePath]: change } })),
  clearRealtimeChange: (filePath) =>
    set((s) => {
      const next = { ...s.realtimeChanges };
      delete next[filePath];
      return { realtimeChanges: next };
    }),
  clearAllRealtimeChanges: () => set({ realtimeChanges: {} }),
  activeSessionId: null,
  editorCollapsed: (() => {
    if (typeof localStorage === "undefined") return false;
    return localStorage.getItem("repolens.editorCollapsed") === "true";
  })(),
  setRepoId: (id) => {
    if (typeof localStorage !== "undefined") {
      if (id == null) localStorage.removeItem("repolens.repoId");
      else localStorage.setItem("repolens.repoId", String(id));
    }
    set({
      repoId: id,
      groups: emptyGroups(),
      activeGroupIndex: 0,
      tabs: [],
      activePath: null,
      revealTarget: null,
      indexStale: false,
    });
  },
  openFile: (path, line) =>
    set((st) => {
      const gi = st.activeGroupIndex;
      const groups = st.groups.map((g, i) => {
        if (i !== gi) return g;
        const tabs = g.tabs.some((t) => t.path === path)
          ? g.tabs
          : [...g.tabs, { path, dirty: false }];
        return { tabs, activePath: path };
      });
      // C3: Auto-expand the editor when a file is opened while it is collapsed.
      if (st.editorCollapsed && typeof localStorage !== "undefined") {
        localStorage.setItem("repolens.editorCollapsed", "false");
      }
      return {
        ...mirror(groups, gi),
        view: "editor" as const,
        editorCollapsed: false,
        revealTarget:
          line != null
            ? { path, line, nonce: (st.revealTarget?.nonce ?? 0) + 1 }
            : st.revealTarget,
      };
    }),
  closeTab: (path, groupIndex) =>
    set((st) => {
      const gi = groupIndex ?? st.activeGroupIndex;
      const g = st.groups[gi];
      if (!g) return st;
      const idx = g.tabs.findIndex((t) => t.path === path);
      if (idx < 0) return st;
      const tabs = g.tabs.filter((t) => t.path !== path);
      let activePath = g.activePath;
      if (g.activePath === path) {
        activePath = (tabs[idx] ?? tabs[idx - 1])?.path ?? null;
      }
      const groups = st.groups.map((gg, i) => (i === gi ? { tabs, activePath } : gg));
      return mirror(groups, st.activeGroupIndex);
    }),
  setActive: (path, groupIndex) =>
    set((st) => {
      const gi = groupIndex ?? st.activeGroupIndex;
      if (!st.groups[gi]) return st;
      const groups = st.groups.map((g, i) => (i === gi ? { ...g, activePath: path } : g));
      return mirror(groups, st.activeGroupIndex);
    }),
  setDirty: (path, dirty) =>
    set((st) => {
      const groups = st.groups.map((g) => ({
        ...g,
        tabs: g.tabs.map((t) => (t.path === path ? { ...t, dirty } : t)),
      }));
      return mirror(groups, st.activeGroupIndex);
    }),
  setActiveGroup: (index) =>
    set((st) => {
      if (index < 0 || index >= st.groups.length) return st;
      return mirror(st.groups, index);
    }),
  splitEditor: () =>
    set((st) => {
      if (st.groups.length >= 2) return st;
      const cur = st.groups[st.activeGroupIndex];
      const activeTab = cur?.tabs.find((t) => t.path === cur.activePath);
      const newGroup: EditorGroup = activeTab
        ? { tabs: [{ path: activeTab.path, dirty: activeTab.dirty }], activePath: activeTab.path }
        : { tabs: [], activePath: null };
      const groups = [...st.groups, newGroup];
      return { ...mirror(groups, groups.length - 1), view: "editor" as const };
    }),
  closeGroup: (index) =>
    set((st) => {
      if (st.groups.length <= 1) return st;
      if (index < 0 || index >= st.groups.length) return st;
      const groups = st.groups.filter((_, i) => i !== index);
      // 关闭后仅剩一组，活动组回到 0（满足“关闭活动组则回到 0”且不低于 1 组）。
      return mirror(groups, 0);
    }),
  // 切换到任何在编辑器区渲染的视图时，若编辑器被折叠则自动展开——
  // 用户点左侧「图/需求流/agent轨迹」等需要编辑器区的入口时它会自动打开。
  setView: (v) => set({ view: v, editorCollapsed: false }),
  openAgentRun: (runId) => set({ view: "agentruns", activeAgentRunId: runId, editorCollapsed: false }),
  openRequirementInsight: (reqId) =>
    set({ view: "requirements", activeRequirementId: reqId, editorCollapsed: false }),
  clearActiveRequirementId: () => set({ activeRequirementId: undefined }),
  setSidebarMode: (m) => set({ sidebarMode: m }),
  toggleTerminal: () => set((st) => ({ terminalVisible: !st.terminalVisible })),
  markIndexStale: () => set({ indexStale: true }),
  clearIndexStale: () => set({ indexStale: false }),
  refreshTree: () => set((st) => ({ treeRefreshNonce: st.treeRefreshNonce + 1 })),
  requestNewChat: () => set((st) => ({ newChatNonce: st.newChatNonce + 1 })),
  requestImportFolder: () =>
    set((st) => ({
      importFolderNonce: st.importFolderNonce + 1,
      sidebarMode: "explorer" as const,
      view: "editor" as const,
    })),
  requestGitImport: () =>
    set((st) => ({
      gitImportNonce: st.gitImportNonce + 1,
      sidebarMode: "explorer" as const,
      view: "editor" as const,
    })),
  focusChat: () => set((st) => ({ focusChatNonce: st.focusChatNonce + 1 })),
  requestSave: () => set((st) => ({ saveNonce: st.saveNonce + 1 })),
  clearReveal: () => set({ revealTarget: null }),
  setCursor: (c) => set({ cursor: c }),
  setRightEngine: (engine) => {
    if (typeof localStorage !== "undefined") {
      localStorage.setItem("repolens.rightEngine", engine);
    }
    set({ rightEngine: engine });
  },
  bumpFileReload: (path) =>
    set((st) => ({
      fileReloadNonces: {
        ...st.fileReloadNonces,
        [path]: (st.fileReloadNonces[path] ?? 0) + 1,
      },
    })),
  setConflictPaths: (paths) => set({ conflictPaths: paths }),
  clearConflictPath: (path) =>
    set((st) => ({ conflictPaths: st.conflictPaths.filter((p) => p !== path) })),
  setActiveSessionId: (id) => set({ activeSessionId: id }),
  setEditorCollapsed: (collapsed) => {
    if (typeof localStorage !== "undefined") {
      localStorage.setItem("repolens.editorCollapsed", String(collapsed));
    }
    set({ editorCollapsed: collapsed });
  },
}));
