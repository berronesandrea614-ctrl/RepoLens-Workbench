import { describe, expect, it } from "vitest";
import { fuzzyScore } from "./fuzzy";

describe("fuzzyScore", () => {
  it("returns -1 when not a subsequence", () => {
    expect(fuzzyScore("xyz", "UserService.java")).toBe(-1);
  });
  it("matches case-insensitive subsequence", () => {
    expect(fuzzyScore("usrv", "UserService.java")).toBeGreaterThan(0);
  });
  it("prefers consecutive and boundary hits", () => {
    const exact = fuzzyScore("user", "src/UserService.java");
    const scattered = fuzzyScore("user", "u/s/e/r/x.java");
    expect(exact).toBeGreaterThan(scattered);
  });
  it("empty query matches everything with score 0", () => {
    expect(fuzzyScore("", "a.java")).toBe(0);
  });
});
