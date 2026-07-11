import { describe, expect, it } from "vitest";
import { formatAdrNumber, statusClass, statusLabel } from "./adrApi";

describe("formatAdrNumber", () => {
  it("formats null as em-dash", () => {
    expect(formatAdrNumber(null)).toBe("—");
  });
  it("pads number to 4 digits", () => {
    expect(formatAdrNumber(1)).toBe("0001");
    expect(formatAdrNumber(42)).toBe("0042");
    expect(formatAdrNumber(1000)).toBe("1000");
  });
});

describe("statusClass", () => {
  it("maps PROPOSED to proposed", () => {
    expect(statusClass("PROPOSED")).toBe("proposed");
  });
  it("maps ACCEPTED to accepted", () => {
    expect(statusClass("ACCEPTED")).toBe("accepted");
  });
  it("maps SUPERSEDED to superseded", () => {
    expect(statusClass("SUPERSEDED")).toBe("superseded");
  });
  it("maps unknown string to unknown", () => {
    expect(statusClass("DRAFT")).toBe("unknown");
  });
});

describe("statusLabel", () => {
  it("returns 草案 for PROPOSED", () => {
    expect(statusLabel("PROPOSED")).toBe("草案");
  });
  it("returns 已采纳 for ACCEPTED", () => {
    expect(statusLabel("ACCEPTED")).toBe("已采纳");
  });
  it("returns 已废弃 for SUPERSEDED", () => {
    expect(statusLabel("SUPERSEDED")).toBe("已废弃");
  });
  it("returns the raw string for unknown status", () => {
    expect(statusLabel("CUSTOM")).toBe("CUSTOM");
  });
});
