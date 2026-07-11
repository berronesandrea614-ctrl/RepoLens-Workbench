import type { MessageKey } from "./zh";

/**
 * 英文覆盖字典。仅需给出与 zh 相同 key 的英文文案；缺失的 key 会由 t() 自动回退到
 * zh.ts 的中文原文（因此这里不必列全所有 key，但主界面 chrome 应尽量覆盖）。
 *
 * Partial<Record<MessageKey, string>>：类型受 zh 的 key 集合约束，写错 key 会编译报错。
 */
export const en: Partial<Record<MessageKey, string>> = {
  // ── Common ──────────────────────────────────────────────
  "common.save": "Save",
  "common.cancel": "Cancel",
  "common.delete": "Delete",
  "common.refresh": "Refresh",
  "common.close": "Close",
  "common.loading": "Loading…",
  "common.confirm": "Confirm",
  "common.ok": "OK",
  "common.retry": "Retry",
  "common.open": "Open",
  "common.saving": "Saving…",

  // ── Title bar ───────────────────────────────────────────
  "titlebar.noRepo": "No repository open",
  "titlebar.showEditor": "Show editor",
  "titlebar.hideEditor": "Hide editor",
  "titlebar.showEditorTip": "Show the center editor area",
  "titlebar.hideEditorTip": "Hide the center editor area (opens automatically when you click a file or a left-side view)",

  // ── Right engine tabs ───────────────────────────────────
  "engine.chat": "AI Chat",
  "engine.claude": "Claude Code",

  // ── ActivityBar tooltips ────────────────────────────────
  "activity.explorer": "Explorer",
  "activity.search": "Search",
  "activity.terminal": "Terminal",
  "activity.showEditor": "Show editor",
  "activity.hideEditor": "Hide editor",
  "activity.chat": "AI Chat (right panel)",
  "activity.settings": "Settings",
  "activity.reorderHint": " (hold to drag and reorder)",

  // ── ActivityBar view tools ──────────────────────────────
  "tool.graph": "Data Flow",
  "tool.requirements": "Requirements",
  "tool.agentruns": "Agent Runs",
  "tool.debt": "Understanding Debt",
  "tool.provenance": "AI Provenance",
  "tool.traceability": "Spec ↔ Impl Trace",
  "tool.adr": "Auto ADR",
  "tool.sensitive": "Sensitive Files",
  "tool.mission": "Mission Control",
  "tool.timeline": "Timeline",
  "tool.drift": "Architecture Drift",
  "tool.branch": "Solution Branches",

  // ── Sidebar ─────────────────────────────────────────────
  "sidebar.explorer": "Explorer",
  "sidebar.search": "Search",

  // ── Repo picker ─────────────────────────────────────────
  "repo.label": "Repository",
  "repo.select": "Select a repository",
  "repo.openFolder": "Open Folder…",
  "repo.gitUrl": "Git URL",
  "repo.deleteCurrent": "Delete current repository",
  "repo.importFolderTip": "Import a local folder",
  "repo.importGitTip": "Import from a Git URL",
  "repo.folderAbsPath": "Folder absolute path",
  "repo.gitAddress": "Git repository URL",
  "repo.branch": "Branch",
  "repo.name": "Repository name",
  "repo.openExisting": "Open existing",
  "repo.reindex": "Reindex",
  "repo.startImport": "Start import",
  "repo.importing": "Importing…",

  // ── Status bar ──────────────────────────────────────────
  "status.noRepo": "No repository open",
  "status.reindexing": "Reindexing…",
  "status.staleIndex": "Index may be stale · click to rebuild",

  // ── Chat panel ──────────────────────────────────────────
  "chat.title": "AI Chat",
  "chat.newChat": "New chat",
  "chat.history": "History",
  "chat.memory": "Long-term memory",
  "chat.localBadge": "🔒 Local",
  "chat.localBadgeTip": "All inference runs locally / on intranet; code never leaves the machine",
  "chat.multiSolution": "Multi-solution",
  "chat.realtime": "Live",
  "chat.send": "Send",
  "chat.thinking": "Thinking…",
  "chat.noRepoTitle": "No repository open",
  "chat.noRepoSub": "Import a repository to start an AI chat",
  "chat.welcomeTitle": "✳ RepoLens Agent",
  "chat.welcomeSub": "In-house AI kernel · edits directly in your opened folder · git-revertible",
  "chat.placeholderNoRepo": "Please import and select a repository first",
  "chat.emptyHistory": "No history yet — start a conversation",
  "chat.untitledSession": "Untitled session",

  // ── Claude Code panel ───────────────────────────────────
  "claude.view": "View",
  "claude.single": "Single pane",
  "claude.split": "Two panes",
  "claude.pickProject": "← Pick a local project on the left to launch Claude Code",

  // ── Settings ────────────────────────────────────────────
  "settings.title": "Settings",
  "settings.sectionLlm": "Language Model (LLM)",
  "settings.sectionEmbedding": "Embedding",
  "settings.sectionRules": "Project Rules (AGENTS.md)",
  "settings.sectionPrivacy": "Privacy & Egress",
  "settings.sectionVerify": "Chain Verification & Zero-Egress Proof",
  "settings.sectionAppearance": "Appearance & Language",
  "settings.sectionAccount": "Account",
  "settings.testConnection": "Test connection",
  "settings.testing": "Testing…",
  "settings.save": "Save",
  "settings.savePrivacy": "Save mode",
  "settings.refreshRecords": "Refresh records",
  "settings.uiLanguage": "Interface language",
  "settings.uiLanguageDesc": "Choose the app interface language.",
  "settings.langZh": "简体中文",
  "settings.langEn": "English",
  "settings.changePassword": "Change password",
  "settings.logout": "Log out",
};
