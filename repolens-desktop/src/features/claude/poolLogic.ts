/**
 * poolLogic.ts — Pure pool / LRU helpers for the Claude Code multi-project manager.
 *
 * All functions are side-effect-free and fully unit-testable with vitest.
 * The claudeStore imports these and combines them with Zustand state + Tauri side effects.
 */

// ─────────────────────────────────────────────────────────────
//  Types
// ─────────────────────────────────────────────────────────────

export type SessionStatus = "live" | "dormant" | "running";

/** Per-project session entry held in claudeStore. */
export interface ProjectSession {
  repoId: number;
  realDir: string;
  /** PTY identifier (10000 + repoId). Undefined when dormant / not spawned yet. */
  ptyId?: number;
  /**
   * Last captured Claude session id for --resume.
   * Kept as a field for a future pass; not actively used.
   */
  lastSessionId?: string;
  status: SessionStatus;
}

// ─────────────────────────────────────────────────────────────
//  Same-directory lookup (C1: duplicate-import guard)
// ─────────────────────────────────────────────────────────────

/**
 * Return the repoId of any LIVE session (ptyId != null) whose realDir matches
 * the given directory exactly.  Returns null if none found.
 *
 * Used by ClaudeCodePanel.doActivate to reuse an existing terminal instead of
 * spawning a second `claude` process in the same working directory.
 */
export function findLiveSessionByDir(
  sessions: Record<number, ProjectSession>,
  realDir: string,
): number | null {
  for (const [id, s] of Object.entries(sessions)) {
    if (s.ptyId != null && s.realDir === realDir) {
      return Number(id);
    }
  }
  return null;
}

// ─────────────────────────────────────────────────────────────
//  LRU helpers
// ─────────────────────────────────────────────────────────────

/**
 * Return a new LRU list with repoId moved to the front (index 0 = most-recently used).
 * Duplicate entries are removed so repoId appears exactly once.
 */
export function lruTouch(lruOrder: number[], repoId: number): number[] {
  return [repoId, ...lruOrder.filter((id) => id !== repoId)];
}

// ─────────────────────────────────────────────────────────────
//  Pool capacity helpers
// ─────────────────────────────────────────────────────────────

/** Count sessions that currently hold a PTY slot (ptyId is set). */
export function countLive(sessions: Record<number, ProjectSession>): number {
  return Object.values(sessions).filter((s) => s.ptyId != null).length;
}

/** True when the pool has reached its capacity. */
export function isPoolFull(
  sessions: Record<number, ProjectSession>,
  poolSize: number
): boolean {
  return countLive(sessions) >= poolSize;
}

// ─────────────────────────────────────────────────────────────
//  Eviction
// ─────────────────────────────────────────────────────────────

/**
 * Select the least-recently-used eviction candidate:
 *   - Must have an active PTY (ptyId != null).
 *   - Must NOT be "running" (running sessions are protected from auto-eviction).
 *   - Must NOT be the currently-active project (the one shown on screen).
 *
 * Walks the LRU list from tail (oldest) to head (newest).
 * Returns the repoId of the candidate, or null if no evictable session exists.
 */
export function selectEvictTarget(
  sessions: Record<number, ProjectSession>,
  lruOrder: number[],
  activeRepoId?: number | null
): number | null {
  for (let i = lruOrder.length - 1; i >= 0; i--) {
    const id = lruOrder[i];
    const s = sessions[id];
    if (s && s.ptyId != null && s.status !== "running" && id !== activeRepoId) {
      return id;
    }
  }
  return null;
}

// ─────────────────────────────────────────────────────────────
//  Spawn argument computation
// ─────────────────────────────────────────────────────────────

/**
 * Compute the spawn arguments for a project session.
 *
 * Always returns `[]` (plain `claude` — fresh session, no --continue).
 *
 * Rationale: live-pool projects keep context via the running PTY; dormant
 * projects that are re-opened should start with an empty context rather than
 * resuming a potentially stale (days-old) session with a large token budget.
 * Context continuity for active sessions is provided by PTY survival, not
 * by session-resume flags.
 */
export function computeSpawnArgs(
  _session: ProjectSession | undefined
): string[] {
  return [];
}

// ─────────────────────────────────────────────────────────────
//  planActivate — pure computation of the full activate action
// ─────────────────────────────────────────────────────────────

export interface ActivatePlan {
  /** repoId to evict (caller must call term_kill(evictPtyId)), or null. */
  evictRepoId: number | null;
  /** ptyId of the evicted session needed for term_kill. */
  evictPtyId?: number;
  /**
   * Whether the caller must spawn a new PTY.
   * False when the project was already live (just switching display).
   */
  needsSpawn: boolean;
  /** PTY id for the activated session (10000 + repoId). */
  ptyId: number;
  /** Arguments to pass to `claude` CLI on spawn. */
  spawnArgs: string[];
  /** Updated sessions record (immutable — new object). */
  sessions: Record<number, ProjectSession>;
  /** Updated LRU order (immutable — new array). */
  lruOrder: number[];
}

// ─────────────────────────────────────────────────────────────
//  computeVisible — which repoIds to show on screen (§3c.2)
// ─────────────────────────────────────────────────────────────

/**
 * Compute the list of repoIds that should be displayed on screen.
 *
 * "single" → [activeRepoId] (or [] when null).
 * "split-2" → up to 2 live repoIds from lruOrder; activeRepoId is first
 *             when it has a live PTY, then the next live LRU entry fills
 *             the second slot.
 *
 * Only sessions with ptyId != null are candidates — dormant sessions have
 * no terminal buffer to display.
 */
export function computeVisible(
  layout: "single" | "split-2",
  activeRepoId: number | null,
  lruOrder: number[],
  sessions: Record<number, ProjectSession>,
): number[] {
  if (layout === "single") {
    return activeRepoId != null ? [activeRepoId] : [];
  }
  // split-2: prefer activeRepoId at slot 0, next live LRU entry at slot 1.
  const live = lruOrder.filter((id) => sessions[id]?.ptyId != null);
  if (activeRepoId != null && live.includes(activeRepoId)) {
    const rest = live.filter((id) => id !== activeRepoId);
    return [activeRepoId, ...rest].slice(0, 2);
  }
  return live.slice(0, 2);
}

// ─────────────────────────────────────────────────────────────

/**
 * Pure function — compute everything that must happen when a project is activated.
 *
 * Does NOT call term_kill or term_spawn; the caller is responsible for those
 * side effects so this function remains fully testable.
 *
 * @param activeRepoId - The currently-displayed project (excluded from eviction candidates).
 */
export function planActivate(
  sessions: Record<number, ProjectSession>,
  lruOrder: number[],
  poolSize: number,
  repoId: number,
  realDir: string,
  activeRepoId?: number | null
): ActivatePlan {
  const ptyId = 10000 + repoId;
  const existing = sessions[repoId];

  // ── Case 1: already live (PTY exists) ──────────────────────
  if (existing?.ptyId != null) {
    return {
      evictRepoId: null,
      needsSpawn: false,
      ptyId: existing.ptyId,
      spawnArgs: [],
      sessions,
      lruOrder: lruTouch(lruOrder, repoId),
    };
  }

  let nextSessions = { ...sessions };
  let nextLru = lruOrder;
  let evictRepoId: number | null = null;
  let evictPtyId: number | undefined;

  // ── Case 2: pool full → evict LRU non-running non-active ───
  if (isPoolFull(nextSessions, poolSize)) {
    evictRepoId = selectEvictTarget(nextSessions, nextLru, activeRepoId);
    if (evictRepoId != null) {
      const evictSession = nextSessions[evictRepoId]!;
      evictPtyId = evictSession.ptyId;
      nextSessions = {
        ...nextSessions,
        [evictRepoId]: {
          ...evictSession,
          ptyId: undefined,
          status: "dormant",
        },
      };
    }
  }

  // ── Create / reactivate session ─────────────────────────────
  const spawnArgs = computeSpawnArgs(existing);
  nextSessions = {
    ...nextSessions,
    [repoId]: {
      repoId,
      realDir,
      ptyId,
      status: "live",
      lastSessionId: existing?.lastSessionId,
    },
  };
  nextLru = lruTouch(nextLru, repoId);

  return {
    evictRepoId,
    evictPtyId,
    needsSpawn: true,
    ptyId,
    spawnArgs,
    sessions: nextSessions,
    lruOrder: nextLru,
  };
}
