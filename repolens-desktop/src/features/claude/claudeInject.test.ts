import { describe, it, expect } from "vitest";
import { buildInjectionText } from "./claudeInject";

describe("buildInjectionText", () => {
  // ── No selection → @path ──────────────────────────────────

  it("no selection → @<path> with trailing space", () => {
    expect(buildInjectionText("src/App.tsx")).toBe("@src/App.tsx ");
  });

  it("empty string selection → @<path> with trailing space", () => {
    expect(buildInjectionText("src/App.tsx", "")).toBe("@src/App.tsx ");
  });

  it("whitespace-only selection → treated as no selection", () => {
    expect(buildInjectionText("src/App.tsx", "   \n  ")).toBe("@src/App.tsx ");
  });

  it("deep relative path → @ includes full relative path", () => {
    expect(buildInjectionText("a/b/c/d.ts")).toBe("@a/b/c/d.ts ");
  });

  it("filename only (no directory) → @ includes filename", () => {
    expect(buildInjectionText("main.rs")).toBe("@main.rs ");
  });

  // ── With selection → fenced code block ───────────────────

  it("selection → fenced block with filename as info-string", () => {
    const result = buildInjectionText("src/foo/Bar.tsx", "const x = 1;");
    expect(result).toBe("```Bar.tsx\nconst x = 1;\n```\n");
  });

  it("selection in root file → filename == path", () => {
    const result = buildInjectionText("README.md", "# Hello");
    expect(result).toBe("```README.md\n# Hello\n```\n");
  });

  it("multi-line selection preserves newlines", () => {
    const sel = "function foo() {\n  return 42;\n}";
    const result = buildInjectionText("src/utils.ts", sel);
    expect(result).toBe("```utils.ts\nfunction foo() {\n  return 42;\n}\n```\n");
  });

  it("selection from deeply nested file uses only basename for info-string", () => {
    const result = buildInjectionText("a/b/c/deep.java", "class X {}");
    expect(result).toBe("```deep.java\nclass X {}\n```\n");
  });

  it("selection string that contains backticks is preserved verbatim", () => {
    const result = buildInjectionText("template.ts", "const t = `hello`;");
    expect(result).toContain("`hello`");
  });
});
