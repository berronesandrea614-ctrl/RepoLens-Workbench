/**
 * computeVisible.test.ts — unit tests for the §3c.2 layout helper.
 *
 * computeVisible(layout, activeRepoId, lruOrder, sessions) → number[]
 *
 * Rules:
 *   "single" → [activeRepoId] or []
 *   "split-2" → up to 2 live repoIds; activeRepoId goes first (if live)
 */

import { describe, expect, it } from "vitest";
import { computeVisible, type ProjectSession } from "./poolLogic";

// ─────────────────────────────────────────────────────────────
//  Test helpers
// ─────────────────────────────────────────────────────────────

function live(repoId: number): ProjectSession {
  return {
    repoId,
    realDir: `/projects/repo${repoId}`,
    status: "live",
    ptyId: 10000 + repoId,
  };
}

function dormant(repoId: number): ProjectSession {
  return {
    repoId,
    realDir: `/projects/repo${repoId}`,
    status: "dormant",
    ptyId: undefined,
  };
}

function running(repoId: number): ProjectSession {
  return {
    repoId,
    realDir: `/projects/repo${repoId}`,
    status: "running",
    ptyId: 10000 + repoId,
  };
}

// ─────────────────────────────────────────────────────────────
//  "single" layout
// ─────────────────────────────────────────────────────────────

describe("computeVisible — single layout", () => {
  it("returns [] when activeRepoId is null", () => {
    expect(computeVisible("single", null, [], {})).toEqual([]);
  });

  it("returns [activeRepoId] when active", () => {
    const sessions = { 1: live(1) };
    expect(computeVisible("single", 1, [1], sessions)).toEqual([1]);
  });

  it("returns [activeRepoId] even when other live sessions exist", () => {
    const sessions = { 1: live(1), 2: live(2), 3: live(3) };
    expect(computeVisible("single", 2, [1, 2, 3], sessions)).toEqual([2]);
  });

  it("returns [activeRepoId] even when session is dormant (no PTY check in single)", () => {
    // single mode just wraps activeRepoId — it does not filter by live status.
    const sessions = { 1: dormant(1) };
    expect(computeVisible("single", 1, [1], sessions)).toEqual([1]);
  });

  it("returns [] when activeRepoId is null regardless of live sessions", () => {
    const sessions = { 1: live(1), 2: live(2) };
    expect(computeVisible("single", null, [1, 2], sessions)).toEqual([]);
  });
});

// ─────────────────────────────────────────────────────────────
//  "split-2" layout
// ─────────────────────────────────────────────────────────────

describe("computeVisible — split-2 layout", () => {
  it("returns [] when no live sessions exist", () => {
    const sessions = { 1: dormant(1), 2: dormant(2) };
    expect(computeVisible("split-2", null, [1, 2], sessions)).toEqual([]);
  });

  it("returns [only live one] when exactly 1 live session", () => {
    const sessions = { 1: live(1), 2: dormant(2) };
    expect(computeVisible("split-2", 1, [1, 2], sessions)).toEqual([1]);
  });

  it("returns up to 2 live sessions from lruOrder", () => {
    const sessions = { 1: live(1), 2: live(2), 3: live(3) };
    // lruOrder = [1, 2, 3]; activeRepoId = 1 → [1, 2]
    expect(computeVisible("split-2", 1, [1, 2, 3], sessions)).toEqual([1, 2]);
  });

  it("places activeRepoId at slot 0 even if not first in lruOrder", () => {
    const sessions = { 1: live(1), 2: live(2) };
    // lruOrder puts 1 first, but activeRepoId = 2 → [2, 1]
    expect(computeVisible("split-2", 2, [1, 2], sessions)).toEqual([2, 1]);
  });

  it("skips dormant sessions when building the visible list", () => {
    const sessions = { 1: live(1), 2: dormant(2), 3: live(3) };
    // lruOrder = [1, 2, 3]; 2 is dormant → [1, 3]
    expect(computeVisible("split-2", 1, [1, 2, 3], sessions)).toEqual([1, 3]);
  });

  it("handles running sessions (ptyId set) as live candidates", () => {
    const sessions = { 1: running(1), 2: running(2) };
    expect(computeVisible("split-2", 1, [1, 2], sessions)).toEqual([1, 2]);
  });

  it("returns first 2 live from lruOrder when activeRepoId is null", () => {
    const sessions = { 3: live(3), 2: live(2), 1: live(1) };
    // lruOrder = [3, 2, 1] → first 2 live = [3, 2]
    expect(computeVisible("split-2", null, [3, 2, 1], sessions)).toEqual([3, 2]);
  });

  it("caps result at 2 even if more live sessions exist", () => {
    const sessions = { 1: live(1), 2: live(2), 3: live(3), 4: live(4) };
    const result = computeVisible("split-2", 1, [1, 2, 3, 4], sessions);
    expect(result).toHaveLength(2);
    expect(result[0]).toBe(1); // activeRepoId first
  });

  it("activeRepoId not live → falls through to plain lruOrder slice", () => {
    const sessions = { 1: dormant(1), 2: live(2), 3: live(3) };
    // activeRepoId=1 is dormant → not in live list → return [2, 3]
    expect(computeVisible("split-2", 1, [1, 2, 3], sessions)).toEqual([2, 3]);
  });

  it("returns [] when sessions record is empty", () => {
    expect(computeVisible("split-2", null, [], {})).toEqual([]);
  });
});
