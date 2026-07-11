import { describe, expect, it } from "vitest";
import { reconcileRepoId } from "./repoReconcile";
import type { RepoVO } from "../../api/repoApi";

const repos: RepoVO[] = [
  { id: 1, repoName: "alpha" },
  { id: 2, repoName: "beta" },
];

describe("reconcileRepoId", () => {
  it("returns null when persistedId is null", () => {
    expect(reconcileRepoId(null, repos)).toBeNull();
  });

  it("returns the id when it is found in the list", () => {
    expect(reconcileRepoId(1, repos)).toBe(1);
    expect(reconcileRepoId(2, repos)).toBe(2);
  });

  it("returns null when id is not in the list (stale after user switch)", () => {
    expect(reconcileRepoId(99, repos)).toBeNull();
  });

  it("returns null when list is empty (new user with no repos)", () => {
    expect(reconcileRepoId(5, [])).toBeNull();
  });

  it("returns null when persistedId is null and list is also empty", () => {
    expect(reconcileRepoId(null, [])).toBeNull();
  });
});
