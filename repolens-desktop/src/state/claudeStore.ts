/**
 * claudeStore — Zustand store for the Claude Code multi-project session manager.
 *
 * Responsibilities:
 *   - Track per-project session state (live / dormant / running).
 *   - Maintain LRU order and pool capacity.
 *   - Persist lightweight metadata to localStorage (no chat content — Claude does that).
 *   - Expose prepareActivate() which computes an ActivatePlan (pure) and applies it to
 *     state in one synchronous call.  The caller (ClaudeCodePanel) executes the Tauri
 *     side effects (term_kill + term_spawn) based on the returned plan.
 */

import { create } from "zustand";
import {
  planActivate,
  computeVisible,
  type ActivatePlan,
  type ProjectSession,
  type SessionStatus,
} from "../features/claude/poolLogic";
import type { FeishuBinding } from "../api/feishuApi";

// ─────────────────────────────────────────────────────────────
//  localStorage keys
// ─────────────────────────────────────────────────────────────

const POOL_SIZE_KEY = "repolens.claude.poolSize";
const PROJECTS_KEY = "repolens.claude.projects";
const LRU_KEY = "repolens.claude.lru";

interface StoredProjectMeta {
  lastSessionId?: string;
}

// ─────────────────────────────────────────────────────────────
//  localStorage helpers
// ─────────────────────────────────────────────────────────────

function safeRead<T>(key: string, fallback: T): T {
  if (typeof localStorage === "undefined") return fallback;
  try {
    const v = localStorage.getItem(key);
    return v != null ? (JSON.parse(v) as T) : fallback;
  } catch {
    return fallback;
  }
}

function persistProjects(sessions: Record<number, ProjectSession>): void {
  if (typeof localStorage === "undefined") return;
  const meta: Record<string, StoredProjectMeta> = {};
  for (const [id, s] of Object.entries(sessions)) {
    if (s.lastSessionId != null) {
      meta[id] = { lastSessionId: s.lastSessionId };
    }
  }
  localStorage.setItem(PROJECTS_KEY, JSON.stringify(meta));
}

function persistLru(lruOrder: number[]): void {
  if (typeof localStorage !== "undefined") {
    localStorage.setItem(LRU_KEY, JSON.stringify(lruOrder));
  }
}

// ─────────────────────────────────────────────────────────────
//  Initialise sessions from persisted metadata (all dormant)
// ─────────────────────────────────────────────────────────────

function initSessions(): Record<number, ProjectSession> {
  const meta = safeRead<Record<string, StoredProjectMeta>>(PROJECTS_KEY, {});
  const result: Record<number, ProjectSession> = {};
  for (const [idStr, m] of Object.entries(meta)) {
    const repoId = Number(idStr);
    if (!Number.isFinite(repoId)) continue;
    result[repoId] = {
      repoId,
      realDir: "", // filled when activateProject is first called for this repo
      status: "dormant",
      lastSessionId: m.lastSessionId,
    };
  }
  return result;
}

// ─────────────────────────────────────────────────────────────
//  Store interface
// ─────────────────────────────────────────────────────────────

export interface ClaudeStoreState {
  sessions: Record<number, ProjectSession>;
  lruOrder: number[];
  poolSize: number;
  /** The repoId currently displayed in ClaudeCodePanel. */
  activeRepoId: number | null;

  // ── §3c.2 split-layout ──────────────────────────────────────

  /** Current display layout. "single" = one pane; "split-2" = two side-by-side panes. */
  layout: "single" | "split-2";

  /**
   * Which repoIds are currently shown on screen (values are repoIds, not raw ptyIds).
   * Derived from layout + activeRepoId + lruOrder + sessions; always kept in sync.
   * "single" → [activeRepoId] (or []). "split-2" → up to 2 live repoIds.
   */
  visiblePtyIds: number[];

  /** Switch display layout; automatically recomputes visiblePtyIds. */
  setLayout: (l: "single" | "split-2") => void;

  /** Explicitly override which repos are visible (e.g., after closing a pane). */
  setVisible: (ids: number[]) => void;

  /**
   * Compute an ActivatePlan (pure logic via planActivate) and immediately
   * apply the resulting state update (sessions, lruOrder, activeRepoId).
   *
   * The caller is responsible for Tauri side effects:
   *   - call term_kill(plan.evictPtyId) when plan.evictRepoId != null
   *   - call term_spawn(plan.ptyId, ..., plan.spawnArgs) when plan.needsSpawn
   *   - call markDormant(plan.evictRepoId) after term_kill succeeds
   */
  prepareActivate: (repoId: number, realDir: string) => ActivatePlan;

  /**
   * Mark a session as dormant (clear ptyId) after its PTY has been killed.
   * Called by ClaudeCodePanel after a successful term_kill.
   */
  markDormant: (repoId: number) => void;

  /** Update the runtime status of a session (e.g., "running" while task executes). */
  setStatus: (repoId: number, status: SessionStatus) => void;

  /** Explicitly set the active (displayed) project. */
  setActiveRepoId: (id: number | null) => void;

  /**
   * Resize the pool.  Persisted to localStorage.
   * Does not immediately evict sessions over the new limit (eviction is lazy).
   */
  setPoolSize: (n: number) => void;

  // ── Privacy dismissal (per-repo, backward-compat with CC-1 key) ──

  /** Dismiss the privacy warning for a specific repo. */
  dismissPrivacy: (repoId: number) => void;

  /** Returns true if the privacy notice has been dismissed for this repo. */
  isPrivacyDismissed: (repoId: number) => boolean;

  // ── Feishu bindings (per-repo, for PTY uplink reporter) ──

  /** Cached feishu bindings keyed by repoId. Populated lazily by FeishuBindingPanel / terminal spawn. */
  feishuBindings: Record<number, FeishuBinding[]>;

  /** Replace the cached bindings for a repo (call after list/create/delete). */
  setFeishuBindings: (repoId: number, bindings: FeishuBinding[]) => void;
}

// ─────────────────────────────────────────────────────────────
//  Store implementation
// ─────────────────────────────────────────────────────────────

export const useClaudeStore = create<ClaudeStoreState>((set, get) => ({
  sessions: initSessions(),
  lruOrder: (() => {
    const v = safeRead<unknown>(LRU_KEY, []);
    return Array.isArray(v) ? (v as number[]) : [];
  })(),
  poolSize: (() => {
    const v = safeRead<number>(POOL_SIZE_KEY, 5);
    return Number.isFinite(v) && v > 0 ? v : 5;
  })(),
  activeRepoId: null,
  layout: "single",
  visiblePtyIds: [],
  feishuBindings: {},

  setLayout(l) {
    set((st) => ({
      layout: l,
      visiblePtyIds: computeVisible(l, st.activeRepoId, st.lruOrder, st.sessions),
    }));
  },

  setVisible(ids) {
    set({ visiblePtyIds: ids });
  },

  prepareActivate(repoId, realDir) {
    const { sessions, lruOrder, poolSize, activeRepoId, layout } = get();
    const plan = planActivate(sessions, lruOrder, poolSize, repoId, realDir, activeRepoId);
    set({
      sessions: plan.sessions,
      lruOrder: plan.lruOrder,
      activeRepoId: repoId,
      visiblePtyIds: computeVisible(layout, repoId, plan.lruOrder, plan.sessions),
    });
    persistProjects(plan.sessions);
    persistLru(plan.lruOrder);
    return plan;
  },

  markDormant(repoId) {
    set((st) => {
      const s = st.sessions[repoId];
      if (!s) return st;
      const sessions: Record<number, ProjectSession> = {
        ...st.sessions,
        [repoId]: { ...s, ptyId: undefined, status: "dormant" },
      };
      persistProjects(sessions);
      return {
        sessions,
        visiblePtyIds: computeVisible(st.layout, st.activeRepoId, st.lruOrder, sessions),
      };
    });
  },

  setStatus(repoId, status) {
    set((st) => {
      const s = st.sessions[repoId];
      if (!s) return st;
      return {
        sessions: { ...st.sessions, [repoId]: { ...s, status } },
      };
    });
  },

  setActiveRepoId(id) {
    set((st) => ({
      activeRepoId: id,
      visiblePtyIds: computeVisible(st.layout, id, st.lruOrder, st.sessions),
    }));
  },

  setPoolSize(n) {
    const poolSize = Number.isFinite(n) && n > 0 ? n : 5;
    if (typeof localStorage !== "undefined") {
      localStorage.setItem(POOL_SIZE_KEY, String(poolSize));
    }
    set({ poolSize });
  },

  dismissPrivacy(repoId) {
    if (typeof localStorage !== "undefined") {
      localStorage.setItem(`repolens.claudePrivacyDismissed.${repoId}`, "true");
    }
  },

  isPrivacyDismissed(repoId) {
    if (typeof localStorage === "undefined") return false;
    return localStorage.getItem(`repolens.claudePrivacyDismissed.${repoId}`) === "true";
  },

  setFeishuBindings(repoId, bindings) {
    set((st) => ({
      feishuBindings: { ...st.feishuBindings, [repoId]: bindings },
    }));
  },
}));
