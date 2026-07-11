import { describe, it, expect } from "vitest";
import { nextFrame, prevFrame } from "./timelineHelpers";
import { changeTypeColor } from "../graph/graphLayout";

// ---- nextFrame ----
describe("nextFrame", () => {
  it("increments by 1", () => {
    expect(nextFrame(0, 5)).toBe(1);
    expect(nextFrame(2, 5)).toBe(3);
  });
  it("clamps at last index (total-1)", () => {
    expect(nextFrame(4, 5)).toBe(4);
    expect(nextFrame(9, 10)).toBe(9);
  });
  it("returns 0 for empty timeline (total=0)", () => {
    expect(nextFrame(0, 0)).toBe(0);
  });
  it("returns 0 for single-frame timeline (total=1)", () => {
    expect(nextFrame(0, 1)).toBe(0);
  });
});

// ---- prevFrame ----
describe("prevFrame", () => {
  it("decrements by 1", () => {
    expect(prevFrame(3, 5)).toBe(2);
    expect(prevFrame(1, 5)).toBe(0);
  });
  it("clamps at 0", () => {
    expect(prevFrame(0, 5)).toBe(0);
  });
});

// ---- changeTypeColor ----
describe("changeTypeColor", () => {
  it("returns blue (#4daafc) for NEW", () => {
    expect(changeTypeColor("NEW")).toBe("#4daafc");
  });
  it("returns orange (#f39c12) for MODIFIED", () => {
    expect(changeTypeColor("MODIFIED")).toBe("#f39c12");
  });
  it("returns dark gray (#30363d) for STABLE", () => {
    expect(changeTypeColor("STABLE")).toBe("#30363d");
  });
  it("returns default gray (#484f58) for undefined", () => {
    expect(changeTypeColor(undefined)).toBe("#484f58");
  });
  it("returns default gray (#484f58) for unknown string", () => {
    expect(changeTypeColor("UNKNOWN")).toBe("#484f58");
  });
  it("returns default gray (#484f58) for empty string", () => {
    expect(changeTypeColor("")).toBe("#484f58");
  });
});
