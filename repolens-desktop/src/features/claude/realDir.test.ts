import { describe, expect, it } from "vitest";
import { dedupeByRealDir, repoRealDir } from "./realDir";
import type { RepoVO } from "../../api/repoApi";

function makeRepo(repoUrl?: string): RepoVO {
  return { id: 1, repoName: "test", repoUrl };
}

describe("repoRealDir", () => {
  it("decodes a simple file:// URL to absolute path", () => {
    expect(repoRealDir(makeRepo("file:///Users/alice/myproject"))).toBe(
      "/Users/alice/myproject",
    );
  });

  it("decodes a file:// URL with percent-encoded Chinese characters", () => {
    // "项目" = %E9%A1%B9%E7%9B%AE
    expect(
      repoRealDir(makeRepo("file:///Users/5miles/%E9%A1%B9%E7%9B%AE/repo")),
    ).toBe("/Users/5miles/项目/repo");
  });

  it("decodes a file:// URL with spaces (%20)", () => {
    expect(
      repoRealDir(makeRepo("file:///Users/john%20doe/my%20project")),
    ).toBe("/Users/john doe/my project");
  });

  it("returns null for http remote URL", () => {
    expect(repoRealDir(makeRepo("https://github.com/foo/bar"))).toBeNull();
  });

  it("returns null for git remote URL", () => {
    expect(repoRealDir(makeRepo("git@github.com:foo/bar.git"))).toBeNull();
  });

  it("returns null when repoUrl is undefined", () => {
    expect(repoRealDir(makeRepo(undefined))).toBeNull();
  });

  it("returns null when repo is null", () => {
    expect(repoRealDir(null)).toBeNull();
  });

  it("returns null when repo is undefined", () => {
    expect(repoRealDir(undefined)).toBeNull();
  });

  it("preserves unencoded Chinese characters in the URL", () => {
    // If the URL was built without encoding some chars, they pass through as-is.
    expect(
      repoRealDir(makeRepo("file:///Users/user/项目/repo")),
    ).toBe("/Users/user/项目/repo");
  });
});

// ─────────────────────────────────────────────────────────────
//  dedupeByRealDir (C1: duplicate-import guard)
// ─────────────────────────────────────────────────────────────

function r(id: number, repoUrl?: string): RepoVO {
  return { id, repoName: `repo${id}`, repoUrl };
}

describe("dedupeByRealDir", () => {
  it("removes duplicate local repos pointing to the same directory", () => {
    const repos = [
      r(1, "file:///home/project"),
      r(2, "file:///home/project"), // same dir — duplicate
      r(3, "file:///home/other"),
    ];
    const result = dedupeByRealDir(repos);
    expect(result.map((x) => x.id)).toEqual([1, 3]);
  });

  it("keeps all entries when all dirs are distinct", () => {
    const repos = [
      r(1, "file:///home/a"),
      r(2, "file:///home/b"),
      r(3, "file:///home/c"),
    ];
    expect(dedupeByRealDir(repos)).toHaveLength(3);
  });

  it("keeps all remote repos (no realDir) without deduplication", () => {
    const repos = [
      r(1, "https://github.com/foo/bar"),
      r(2, "https://github.com/foo/bar"),
    ];
    expect(dedupeByRealDir(repos)).toHaveLength(2);
  });

  it("first occurrence wins when duplicates exist", () => {
    const repos = [
      r(10, "file:///home/dup"),
      r(20, "file:///home/dup"),
    ];
    expect(dedupeByRealDir(repos)[0].id).toBe(10);
  });

  it("handles URL-encoded paths consistently", () => {
    const repos = [
      r(1, "file:///Users/5miles/%E9%A1%B9%E7%9B%AE/repo"),
      r(2, "file:///Users/5miles/%E9%A1%B9%E7%9B%AE/repo"), // same encoded path
    ];
    expect(dedupeByRealDir(repos)).toHaveLength(1);
    expect(dedupeByRealDir(repos)[0].id).toBe(1);
  });

  it("returns an empty array for empty input", () => {
    expect(dedupeByRealDir([])).toHaveLength(0);
  });

  it("mixed local and remote: dedupes local, keeps all remote", () => {
    const repos = [
      r(1, "file:///home/project"),
      r(2, "file:///home/project"), // duplicate local
      r(3, "https://github.com/foo/bar"),
    ];
    const result = dedupeByRealDir(repos);
    expect(result.map((x) => x.id)).toEqual([1, 3]);
  });
});
