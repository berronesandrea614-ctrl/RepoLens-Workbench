import { describe, expect, it } from "vitest";
import { avatarColor, initial, filterProjects } from "./projectSwitcherHelpers";
import type { RepoVO } from "../../api/repoApi";

function repo(id: number, name: string, url?: string): RepoVO {
  return { id, repoName: name, repoUrl: url };
}

// ─────────────────────────────────────────────────────────────
//  avatarColor
// ─────────────────────────────────────────────────────────────

describe("avatarColor", () => {
  it("returns a valid hsl() string", () => {
    expect(avatarColor("myproject")).toMatch(/^hsl\(\d+, 55%, 38%\)$/);
  });

  it("is stable — same name always returns the same colour", () => {
    expect(avatarColor("RepoLens")).toBe(avatarColor("RepoLens"));
    expect(avatarColor("quwantongcheng")).toBe(avatarColor("quwantongcheng"));
  });

  it("different names produce different colours in practice", () => {
    expect(avatarColor("alice")).not.toBe(avatarColor("bob"));
    expect(avatarColor("proj-a")).not.toBe(avatarColor("proj-b"));
  });

  it("handles an empty string without throwing", () => {
    expect(() => avatarColor("")).not.toThrow();
    expect(avatarColor("")).toBe("hsl(0, 55%, 38%)");
  });

  it("hue stays within 0–359", () => {
    const samples = ["a", "z", "RepoLens", "quwantongcheng-v1", "12345"];
    for (const s of samples) {
      const m = avatarColor(s).match(/hsl\((\d+),/);
      const hue = parseInt(m![1], 10);
      expect(hue).toBeGreaterThanOrEqual(0);
      expect(hue).toBeLessThan(360);
    }
  });

  it("handles Chinese characters without throwing", () => {
    expect(() => avatarColor("趣完通程")).not.toThrow();
    expect(avatarColor("趣完通程")).toMatch(/^hsl\(\d+, 55%, 38%\)$/);
  });
});

// ─────────────────────────────────────────────────────────────
//  initial
// ─────────────────────────────────────────────────────────────

describe("initial", () => {
  it("returns the first letter uppercased", () => {
    expect(initial("myproject")).toBe("M");
  });

  it("already-uppercase input is preserved", () => {
    expect(initial("Alice")).toBe("A");
  });

  it("returns '?' for an empty string", () => {
    expect(initial("")).toBe("?");
  });

  it("returns '?' for a whitespace-only string", () => {
    expect(initial("   ")).toBe("?");
  });

  it("handles Chinese characters", () => {
    expect(initial("项目")).toBe("项");
  });

  it("trims leading whitespace before taking the initial", () => {
    expect(initial("  zebra")).toBe("Z");
  });

  it("returns a single character", () => {
    expect(initial("hello world")).toHaveLength(1);
  });

  it("handles a single character name", () => {
    expect(initial("x")).toBe("X");
  });
});

// ─────────────────────────────────────────────────────────────
//  filterProjects
// ─────────────────────────────────────────────────────────────

describe("filterProjects", () => {
  const repos: RepoVO[] = [
    repo(1, "myproject", "file:///Users/alice/myproject"),
    repo(2, "other-project", "file:///Users/alice/workspace/other"),
    repo(3, "remote-only", "https://github.com/foo/bar"),
    repo(4, "quwantongcheng-v1", "file:///Users/bob/quwantongcheng-v1"),
    repo(5, "quwantongcheng-v2", "file:///Users/bob/quwantongcheng-v2"),
  ];

  it("returns all repos when query is empty", () => {
    expect(filterProjects(repos, "")).toHaveLength(5);
  });

  it("returns all repos when query is only whitespace", () => {
    expect(filterProjects(repos, "   ")).toHaveLength(5);
  });

  it("filters by repo name (case-insensitive)", () => {
    const result = filterProjects(repos, "MY");
    expect(result.map((r) => r.id)).toEqual([1]);
  });

  it("filters by a path segment in the real dir", () => {
    const result = filterProjects(repos, "workspace");
    expect(result.map((r) => r.id)).toEqual([2]);
  });

  it("matches both repos that share the same name prefix", () => {
    const result = filterProjects(repos, "quwantongcheng");
    const ids = result.map((r) => r.id);
    expect(ids).toContain(4);
    expect(ids).toContain(5);
  });

  it("distinguishes same-prefix repos by their path suffix", () => {
    const result = filterProjects(repos, "v1");
    expect(result.map((r) => r.id)).toEqual([4]);
  });

  it("path-only query — v2 matches only quwantongcheng-v2", () => {
    const result = filterProjects(repos, "v2");
    expect(result.map((r) => r.id)).toEqual([5]);
  });

  it("returns empty array when nothing matches", () => {
    expect(filterProjects(repos, "zzznotfound")).toHaveLength(0);
  });

  it("filters remote repos by name (no real dir)", () => {
    const result = filterProjects(repos, "remote");
    expect(result.map((r) => r.id)).toContain(3);
  });

  it("filters remote repos by URL when name does not match", () => {
    const result = filterProjects(repos, "github");
    expect(result.map((r) => r.id)).toContain(3);
  });

  it("returns an empty array for an empty list regardless of query", () => {
    expect(filterProjects([], "anything")).toHaveLength(0);
  });

  it("handles repos with undefined repoUrl without throwing", () => {
    const noUrl = [repo(99, "nouri", undefined)];
    expect(() => filterProjects(noUrl, "x")).not.toThrow();
    expect(filterProjects(noUrl, "nouri")).toHaveLength(1);
    expect(filterProjects(noUrl, "x")).toHaveLength(0);
  });
});
