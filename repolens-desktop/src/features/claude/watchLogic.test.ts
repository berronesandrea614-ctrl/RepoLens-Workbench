import { describe, it, expect } from "vitest";
import { shouldIgnorePath, classifyChangedFiles } from "./watchLogic";

// ─────────────────────────────────────────────────────────────
//  shouldIgnorePath
// ─────────────────────────────────────────────────────────────

describe("shouldIgnorePath", () => {
  // .git
  it("ignores a file inside .git", () => {
    expect(shouldIgnorePath("/home/user/project/.git/config")).toBe(true);
  });
  it("ignores a nested file inside .git", () => {
    expect(shouldIgnorePath("/home/user/project/.git/refs/heads/main")).toBe(true);
  });
  it("ignores root-relative .git path", () => {
    expect(shouldIgnorePath(".git/HEAD")).toBe(true);
  });
  it("ignores path that IS the .git directory", () => {
    expect(shouldIgnorePath("/home/user/project/.git")).toBe(true);
  });
  it("does NOT ignore a file whose name merely contains git", () => {
    expect(shouldIgnorePath("/home/user/project/src/git-utils.ts")).toBe(false);
  });

  // node_modules
  it("ignores a file inside node_modules", () => {
    expect(shouldIgnorePath("/home/user/project/node_modules/react/index.js")).toBe(true);
  });
  it("ignores nested node_modules", () => {
    expect(shouldIgnorePath("/home/user/project/node_modules/.bin/tsc")).toBe(true);
  });
  it("does NOT ignore a file whose directory name starts with node_modules_extra", () => {
    expect(shouldIgnorePath("/home/user/project/node_modules_extra/foo.ts")).toBe(false);
  });

  // target
  it("ignores a Rust build artifact inside target", () => {
    expect(shouldIgnorePath("/home/user/project/target/debug/build/foo.o")).toBe(true);
  });
  it("ignores path that IS the target directory", () => {
    expect(shouldIgnorePath("/home/user/project/target")).toBe(true);
  });
  it("does NOT ignore /src/target-utils.ts (target as filename)", () => {
    expect(shouldIgnorePath("/home/user/project/src/target-utils.ts")).toBe(false);
  });

  // dist
  it("ignores files inside dist", () => {
    expect(shouldIgnorePath("/home/user/project/dist/index.js")).toBe(true);
  });
  it("does NOT ignore /nodist/file.ts", () => {
    expect(shouldIgnorePath("/home/user/project/nodist/file.ts")).toBe(false);
  });

  // .idea
  it("ignores JetBrains .idea directory", () => {
    expect(shouldIgnorePath("/home/user/project/.idea/workspace.xml")).toBe(true);
  });

  // .DS_Store
  it("ignores .DS_Store anywhere", () => {
    expect(shouldIgnorePath("/home/user/project/src/.DS_Store")).toBe(true);
  });
  it("ignores .DS_Store at root", () => {
    expect(shouldIgnorePath("/home/user/project/.DS_Store")).toBe(true);
  });

  // Normal source files should NOT be ignored
  it("allows a normal TypeScript source file", () => {
    expect(shouldIgnorePath("/home/user/project/src/App.tsx")).toBe(false);
  });
  it("allows a Java source file", () => {
    expect(shouldIgnorePath("/home/user/project/src/main/java/Foo.java")).toBe(false);
  });
  it("allows Cargo.toml", () => {
    expect(shouldIgnorePath("/home/user/project/Cargo.toml")).toBe(false);
  });

  // Windows-style backslashes (normalised)
  it("ignores Windows-style path inside node_modules", () => {
    expect(shouldIgnorePath("C:\\Users\\foo\\project\\node_modules\\react\\index.js")).toBe(true);
  });
});

// ─────────────────────────────────────────────────────────────
//  classifyChangedFiles
// ─────────────────────────────────────────────────────────────

describe("classifyChangedFiles", () => {
  const realDir = "/home/user/myproject";

  it("clean open file → toReload", () => {
    const tabs = [{ path: "src/App.tsx", dirty: false }];
    const result = classifyChangedFiles(
      ["/home/user/myproject/src/App.tsx"],
      tabs,
      realDir,
    );
    expect(result.toReload).toEqual(["src/App.tsx"]);
    expect(result.toConflict).toEqual([]);
  });

  it("dirty open file → toConflict", () => {
    const tabs = [{ path: "src/App.tsx", dirty: true }];
    const result = classifyChangedFiles(
      ["/home/user/myproject/src/App.tsx"],
      tabs,
      realDir,
    );
    expect(result.toReload).toEqual([]);
    expect(result.toConflict).toEqual(["src/App.tsx"]);
  });

  it("unchanged file not in tabs → ignored", () => {
    const tabs = [{ path: "src/Other.tsx", dirty: false }];
    const result = classifyChangedFiles(
      ["/home/user/myproject/src/App.tsx"],
      tabs,
      realDir,
    );
    expect(result.toReload).toEqual([]);
    expect(result.toConflict).toEqual([]);
  });

  it("path outside realDir → ignored", () => {
    const tabs = [{ path: "src/App.tsx", dirty: false }];
    const result = classifyChangedFiles(
      ["/home/other/project/src/App.tsx"],
      tabs,
      realDir,
    );
    expect(result.toReload).toEqual([]);
    expect(result.toConflict).toEqual([]);
  });

  it("mixed batch: one clean, one dirty, one not open", () => {
    const tabs = [
      { path: "src/App.tsx", dirty: false },
      { path: "src/index.ts", dirty: true },
    ];
    const result = classifyChangedFiles(
      [
        "/home/user/myproject/src/App.tsx",
        "/home/user/myproject/src/index.ts",
        "/home/user/myproject/src/NotOpen.ts",
      ],
      tabs,
      realDir,
    );
    expect(result.toReload).toEqual(["src/App.tsx"]);
    expect(result.toConflict).toEqual(["src/index.ts"]);
  });

  it("realDir with trailing slash is handled correctly", () => {
    const tabs = [{ path: "src/App.tsx", dirty: false }];
    const result = classifyChangedFiles(
      ["/home/user/myproject/src/App.tsx"],
      tabs,
      "/home/user/myproject/", // trailing slash
    );
    expect(result.toReload).toEqual(["src/App.tsx"]);
  });

  it("empty changed paths → both lists empty", () => {
    const tabs = [{ path: "src/App.tsx", dirty: false }];
    const result = classifyChangedFiles([], tabs, realDir);
    expect(result.toReload).toEqual([]);
    expect(result.toConflict).toEqual([]);
  });

  it("empty tabs → both lists empty", () => {
    const result = classifyChangedFiles(
      ["/home/user/myproject/src/App.tsx"],
      [],
      realDir,
    );
    expect(result.toReload).toEqual([]);
    expect(result.toConflict).toEqual([]);
  });
});
