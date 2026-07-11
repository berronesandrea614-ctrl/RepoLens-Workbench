import { describe, expect, it } from "vitest";
import {
  computeSpawnArgs,
  countLive,
  findLiveSessionByDir,
  isPoolFull,
  lruTouch,
  planActivate,
  selectEvictTarget,
  type ProjectSession,
} from "./poolLogic";

// ─────────────────────────────────────────────────────────────
//  Test helpers
// ─────────────────────────────────────────────────────────────

function makeSession(
  repoId: number,
  overrides: Partial<ProjectSession> = {}
): ProjectSession {
  return {
    repoId,
    realDir: `/projects/repo${repoId}`,
    status: "live",
    ptyId: 10000 + repoId,
    ...overrides,
  };
}

function dormant(repoId: number): ProjectSession {
  return makeSession(repoId, { ptyId: undefined, status: "dormant" });
}

function running(repoId: number): ProjectSession {
  return makeSession(repoId, { status: "running" });
}

// ─────────────────────────────────────────────────────────────
//  findLiveSessionByDir (C1: duplicate-import guard)
// ─────────────────────────────────────────────────────────────

describe("findLiveSessionByDir", () => {
  it("returns the repoId of a live session whose realDir matches", () => {
    const sessions: Record<number, ProjectSession> = {
      1: makeSession(1),                                 // realDir = /projects/repo1, live
      2: makeSession(2),                                 // realDir = /projects/repo2, live
    };
    expect(findLiveSessionByDir(sessions, "/projects/repo1")).toBe(1);
  });

  it("returns null when no live session matches the directory", () => {
    const sessions: Record<number, ProjectSession> = {
      1: makeSession(1),
    };
    expect(findLiveSessionByDir(sessions, "/projects/no-such-dir")).toBeNull();
  });

  it("ignores dormant sessions (no ptyId) even if realDir matches", () => {
    const sessions: Record<number, ProjectSession> = {
      1: dormant(1),   // realDir = /projects/repo1, but dormant
    };
    expect(findLiveSessionByDir(sessions, "/projects/repo1")).toBeNull();
  });

  it("returns null for empty sessions map", () => {
    expect(findLiveSessionByDir({}, "/projects/repo1")).toBeNull();
  });

  it("finds a running session (running counts as live for this purpose)", () => {
    const sessions: Record<number, ProjectSession> = {
      3: running(3),   // realDir = /projects/repo3, running (ptyId set)
    };
    expect(findLiveSessionByDir(sessions, "/projects/repo3")).toBe(3);
  });

  it("returns the first match when multiple live sessions share a realDir (unexpected but safe)", () => {
    const sessions: Record<number, ProjectSession> = {
      3: { repoId: 3, realDir: "/shared/dir", status: "live", ptyId: 10003 },
      4: { repoId: 4, realDir: "/shared/dir", status: "live", ptyId: 10004 },
    };
    const result = findLiveSessionByDir(sessions, "/shared/dir");
    expect(result === 3 || result === 4).toBe(true); // either is valid
  });
});

// ─────────────────────────────────────────────────────────────
//  lruTouch
// ─────────────────────────────────────────────────────────────

describe("lruTouch", () => {
  it("moves an existing tail repoId to the front", () => {
    expect(lruTouch([1, 2, 3], 3)).toEqual([3, 1, 2]);
  });

  it("moves a middle repoId to the front", () => {
    expect(lruTouch([1, 2, 3], 2)).toEqual([2, 1, 3]);
  });

  it("adds a new repoId to the front of an existing list", () => {
    expect(lruTouch([1, 2], 5)).toEqual([5, 1, 2]);
  });

  it("keeps repoId at front when it is already first", () => {
    expect(lruTouch([1, 2, 3], 1)).toEqual([1, 2, 3]);
  });

  it("does not duplicate an existing repoId", () => {
    const result = lruTouch([3, 1, 2], 1);
    expect(result.filter((x) => x === 1)).toHaveLength(1);
  });

  it("works on an empty list", () => {
    expect(lruTouch([], 7)).toEqual([7]);
  });
});

// ─────────────────────────────────────────────────────────────
//  countLive / isPoolFull
// ─────────────────────────────────────────────────────────────

describe("countLive", () => {
  it("counts only sessions that have a ptyId", () => {
    const sessions: Record<number, ProjectSession> = {
      1: makeSession(1),
      2: dormant(2),
      3: makeSession(3),
    };
    expect(countLive(sessions)).toBe(2);
  });

  it("returns 0 when all sessions are dormant", () => {
    expect(countLive({ 1: dormant(1), 2: dormant(2) })).toBe(0);
  });

  it("counts running sessions (they occupy PTY slots)", () => {
    expect(countLive({ 1: running(1), 2: makeSession(2) })).toBe(2);
  });
});

describe("isPoolFull", () => {
  it("returns true when live count equals poolSize", () => {
    const s = { 1: makeSession(1), 2: makeSession(2), 3: makeSession(3) };
    expect(isPoolFull(s, 3)).toBe(true);
  });

  it("returns true when live count exceeds poolSize", () => {
    const s = { 1: makeSession(1), 2: makeSession(2), 3: makeSession(3) };
    expect(isPoolFull(s, 2)).toBe(true);
  });

  it("returns false when live count is below poolSize", () => {
    const s = { 1: makeSession(1), 2: makeSession(2) };
    expect(isPoolFull(s, 3)).toBe(false);
  });

  it("returns false when all sessions are dormant", () => {
    const s = { 1: dormant(1), 2: dormant(2) };
    expect(isPoolFull(s, 2)).toBe(false);
  });
});

// ─────────────────────────────────────────────────────────────
//  selectEvictTarget
// ─────────────────────────────────────────────────────────────

describe("selectEvictTarget", () => {
  it("returns the LRU tail that is not running and has a ptyId", () => {
    const sessions = { 1: makeSession(1), 2: makeSession(2), 3: makeSession(3) };
    expect(selectEvictTarget(sessions, [3, 2, 1])).toBe(1);
  });

  it("skips running sessions and picks the next evictable one", () => {
    const sessions: Record<number, ProjectSession> = {
      1: running(1),
      2: makeSession(2),
      3: makeSession(3),
    };
    // lru [3, 2, 1]: tail=1 is running → next candidate = 2
    expect(selectEvictTarget(sessions, [3, 2, 1])).toBe(2);
  });

  it("returns null when all live sessions are running", () => {
    const sessions = { 1: running(1), 2: running(2) };
    expect(selectEvictTarget(sessions, [2, 1])).toBeNull();
  });

  it("skips dormant sessions (no ptyId) and finds the evictable live one", () => {
    const sessions = { 1: dormant(1), 2: makeSession(2) };
    expect(selectEvictTarget(sessions, [2, 1])).toBe(2);
  });

  it("returns null for empty sessions", () => {
    expect(selectEvictTarget({}, [])).toBeNull();
  });

  it("returns null when lruOrder is empty even if sessions exist", () => {
    // Shouldn't happen in practice but must be safe.
    expect(selectEvictTarget({ 1: makeSession(1) }, [])).toBeNull();
  });

  it("skips the active project and picks the next LRU candidate", () => {
    const sessions = { 1: makeSession(1), 2: makeSession(2), 3: makeSession(3) };
    // lru [3, 2, 1]: tail=1 is active → next candidate = 2
    expect(selectEvictTarget(sessions, [3, 2, 1], 1)).toBe(2);
  });

  it("returns null when the only evictable live session is the active project", () => {
    // repo 1 is the only live non-running session, but it is the active project
    const sessions: Record<number, ProjectSession> = {
      1: makeSession(1),
      2: running(2),
    };
    expect(selectEvictTarget(sessions, [2, 1], 1)).toBeNull();
  });
});

// ─────────────────────────────────────────────────────────────
//  computeSpawnArgs
// ─────────────────────────────────────────────────────────────

describe("computeSpawnArgs", () => {
  it("returns [] for a dormant session (fresh context, no --continue)", () => {
    expect(computeSpawnArgs(dormant(1))).toEqual([]);
  });

  it("returns [] for undefined (brand-new session)", () => {
    expect(computeSpawnArgs(undefined)).toEqual([]);
  });

  it("returns [] for a live session", () => {
    expect(computeSpawnArgs(makeSession(1))).toEqual([]);
  });

  it("never includes --continue (dormant re-open must be a fresh claude session)", () => {
    expect(computeSpawnArgs(dormant(1))).not.toContain("--continue");
    expect(computeSpawnArgs(undefined)).not.toContain("--continue");
    expect(computeSpawnArgs(makeSession(1))).not.toContain("--continue");
  });
});

// ─────────────────────────────────────────────────────────────
//  planActivate
// ─────────────────────────────────────────────────────────────

describe("planActivate", () => {
  it("already-live project: needsSpawn=false, ptyId unchanged, LRU touched", () => {
    const sessions = { 42: makeSession(42) };
    const plan = planActivate(sessions, [42], 5, 42, "/projects/repo42");
    expect(plan.needsSpawn).toBe(false);
    expect(plan.ptyId).toBe(10042);
    expect(plan.evictRepoId).toBeNull();
    // LRU still contains 42 at front
    expect(plan.lruOrder[0]).toBe(42);
    // sessions unchanged
    expect(plan.sessions).toBe(sessions);
  });

  it("new project below pool limit: needsSpawn=true, no eviction", () => {
    const sessions = { 1: makeSession(1) };
    const plan = planActivate(sessions, [1], 5, 2, "/projects/repo2");
    expect(plan.needsSpawn).toBe(true);
    expect(plan.evictRepoId).toBeNull();
    expect(plan.ptyId).toBe(10002);
    expect(plan.sessions[2]?.status).toBe("live");
    expect(plan.sessions[2]?.ptyId).toBe(10002);
    expect(plan.lruOrder[0]).toBe(2);
  });

  it("pool full: evicts LRU non-running session and spawns new one", () => {
    const sessions: Record<number, ProjectSession> = {
      1: makeSession(1),
      2: makeSession(2),
      3: makeSession(3),
    };
    // repoId 1 is the LRU tail
    const plan = planActivate(sessions, [3, 2, 1], 3, 4, "/projects/repo4");

    expect(plan.evictRepoId).toBe(1);
    expect(plan.evictPtyId).toBe(10001);
    expect(plan.sessions[1]?.status).toBe("dormant");
    expect(plan.sessions[1]?.ptyId).toBeUndefined();
    expect(plan.needsSpawn).toBe(true);
    expect(plan.sessions[4]?.status).toBe("live");
    expect(plan.lruOrder[0]).toBe(4);
  });

  it("running session is never evicted even when it is the LRU tail", () => {
    const sessions: Record<number, ProjectSession> = {
      1: running(1),   // LRU tail — but running
      2: makeSession(2),
      3: makeSession(3),
    };
    const plan = planActivate(sessions, [3, 2, 1], 3, 4, "/projects/repo4");

    // 1 must NOT be evicted
    expect(plan.sessions[1]?.status).toBe("running");
    // 2 (next oldest) gets evicted instead
    expect(plan.evictRepoId).toBe(2);
  });

  it("dormant project reactivated: needsSpawn=true with fresh (empty) spawnArgs", () => {
    const sessions = { 1: dormant(1) };
    const plan = planActivate(sessions, [1], 5, 1, "/projects/repo1");

    expect(plan.needsSpawn).toBe(true);
    expect(plan.spawnArgs).toEqual([]);
    expect(plan.ptyId).toBe(10001);
    expect(plan.sessions[1]?.status).toBe("live");
  });

  it("spawn args are always [] — no --continue, always fresh session", () => {
    const sessions = {};
    const plan = planActivate(sessions, [], 5, 99, "/projects/repo99");
    expect(plan.spawnArgs).toEqual([]);
  });

  it("lruOrder is updated correctly after activation", () => {
    const sessions = { 1: makeSession(1), 2: makeSession(2) };
    const plan = planActivate(sessions, [2, 1], 5, 3, "/projects/repo3");
    expect(plan.lruOrder).toEqual([3, 2, 1]);
  });

  it("active project is never evicted even when it is the LRU tail", () => {
    const sessions: Record<number, ProjectSession> = {
      1: makeSession(1), // LRU tail — but it is the active project
      2: makeSession(2),
      3: makeSession(3),
    };
    // pool size 3, activate new repoId 4, active shown project is 1
    const plan = planActivate(sessions, [3, 2, 1], 3, 4, "/projects/repo4", 1);
    // repoId 1 must NOT be evicted
    expect(plan.sessions[1]?.status).toBe("live");
    // repoId 2 (next oldest non-active non-running) gets evicted
    expect(plan.evictRepoId).toBe(2);
    expect(plan.needsSpawn).toBe(true);
  });

  it("pool full + all running/active: over-cap allow — needsSpawn=true, no eviction", () => {
    // Every live session is protected: one is running, one is active.
    const sessions: Record<number, ProjectSession> = {
      1: running(1),    // protected: running
      2: makeSession(2), // protected: active (passed as activeRepoId)
      3: running(3),    // protected: running
    };
    // pool size 3, all slots taken, nothing is evictable
    const plan = planActivate(sessions, [3, 2, 1], 3, 4, "/projects/repo4", 2);
    expect(plan.evictRepoId).toBeNull();
    expect(plan.needsSpawn).toBe(true);
    expect(plan.sessions[4]?.status).toBe("live");
  });
});
