import { beforeEach, describe, expect, it } from "vitest";
import { useWorkbench } from "./workbenchStore";

const s = () => useWorkbench.getState();

beforeEach(() => {
  useWorkbench.setState({
    repoId: 1,
    groups: [{ tabs: [], activePath: null }],
    activeGroupIndex: 0,
    tabs: [], activePath: null, view: "editor", sidebarMode: "explorer",
    revealTarget: null, terminalVisible: false, indexStale: false, cursor: null,
    treeRefreshNonce: 0, newChatNonce: 0, importFolderNonce: 0, gitImportNonce: 0,
    focusChatNonce: 0, saveNonce: 0, editorCollapsed: false,
  });
});

describe("openFile", () => {
  it("adds tab, activates, switches to editor view (in active group)", () => {
    s().setView("graph");
    s().openFile("a.java");
    expect(s().tabs).toEqual([{ path: "a.java", dirty: false }]);
    expect(s().activePath).toBe("a.java");
    expect(s().groups[0].tabs).toEqual([{ path: "a.java", dirty: false }]);
    expect(s().groups[0].activePath).toBe("a.java");
    expect(s().view).toBe("editor");
  });

  it("does not duplicate existing tab, keeps dirty flag", () => {
    s().openFile("a.java");
    s().setDirty("a.java", true);
    s().openFile("b.java");
    s().openFile("a.java");
    expect(s().tabs.map((t) => t.path)).toEqual(["a.java", "b.java"]);
    expect(s().tabs[0].dirty).toBe(true);
    expect(s().activePath).toBe("a.java");
  });

  it("sets revealTarget with increasing nonce when line given", () => {
    s().openFile("a.java", 10);
    const n1 = s().revealTarget!.nonce;
    s().openFile("a.java", 10);
    expect(s().revealTarget).toMatchObject({ path: "a.java", line: 10 });
    expect(s().revealTarget!.nonce).toBeGreaterThan(n1);
  });

  it("opens into the currently active group", () => {
    s().openFile("a.java");
    s().splitEditor();
    expect(s().activeGroupIndex).toBe(1);
    s().openFile("b.java");
    expect(s().groups[1].tabs.map((t) => t.path)).toEqual(["a.java", "b.java"]);
    expect(s().groups[1].activePath).toBe("b.java");
    // group 0 untouched
    expect(s().groups[0].tabs.map((t) => t.path)).toEqual(["a.java"]);
  });
});

describe("closeTab", () => {
  it("closing active tab activates right neighbor, else left, else null", () => {
    s().openFile("a.java"); s().openFile("b.java"); s().openFile("c.java");
    s().setActive("b.java");
    s().closeTab("b.java");
    expect(s().activePath).toBe("c.java");
    s().closeTab("c.java");
    expect(s().activePath).toBe("a.java");
    s().closeTab("a.java");
    expect(s().activePath).toBeNull();
    expect(s().tabs).toEqual([]);
  });

  it("closing inactive tab keeps active", () => {
    s().openFile("a.java"); s().openFile("b.java");
    s().closeTab("a.java");
    expect(s().activePath).toBe("b.java");
  });

  it("closes a tab in a specified group without affecting others", () => {
    s().openFile("a.java");
    s().splitEditor();
    s().openFile("b.java");
    s().closeTab("a.java", 0);
    expect(s().groups[0].tabs).toEqual([]);
    expect(s().groups[1].tabs.map((t) => t.path)).toEqual(["a.java", "b.java"]);
  });
});

describe("setDirty", () => {
  it("applies dirty to the same path across all groups", () => {
    s().openFile("a.java");
    s().splitEditor(); // group 1 seeded with a.java
    s().setDirty("a.java", true);
    expect(s().groups[0].tabs[0].dirty).toBe(true);
    expect(s().groups[1].tabs[0].dirty).toBe(true);
  });
});

describe("splitEditor / groups", () => {
  it("splits into a second group seeded with the active tab and activates it", () => {
    s().openFile("a.java");
    s().splitEditor();
    expect(s().groups).toHaveLength(2);
    expect(s().groups[1].tabs.map((t) => t.path)).toEqual(["a.java"]);
    expect(s().groups[1].activePath).toBe("a.java");
    expect(s().activeGroupIndex).toBe(1);
    // mirrored top-level reflects active group
    expect(s().activePath).toBe("a.java");
  });

  it("caps at 2 groups", () => {
    s().openFile("a.java");
    s().splitEditor();
    s().splitEditor();
    expect(s().groups).toHaveLength(2);
  });

  it("setActiveGroup switches mirrored tabs/activePath", () => {
    s().openFile("a.java");
    s().splitEditor();
    s().openFile("b.java"); // in group 1
    s().setActiveGroup(0);
    expect(s().activeGroupIndex).toBe(0);
    expect(s().activePath).toBe("a.java");
    s().setActiveGroup(1);
    expect(s().activePath).toBe("b.java");
  });

  it("closeGroup removes a group and returns to a single group at index 0", () => {
    s().openFile("a.java");
    s().splitEditor();
    s().openFile("b.java");
    s().closeGroup(1);
    expect(s().groups).toHaveLength(1);
    expect(s().activeGroupIndex).toBe(0);
    expect(s().activePath).toBe("a.java");
  });

  it("closeGroup never drops below one group", () => {
    s().openFile("a.java");
    s().closeGroup(0);
    expect(s().groups).toHaveLength(1);
  });
});

it("markIndexStale sets flag", () => {
  s().markIndexStale();
  expect(s().indexStale).toBe(true);
});

describe("repoId + cold-start actions", () => {
  it("setRepoId(null) clears repoId, tabs and index-stale flag", () => {
    s().openFile("a.java");
    s().markIndexStale();
    s().setRepoId(null);
    expect(s().repoId).toBeNull();
    expect(s().tabs).toEqual([]);
    expect(s().activePath).toBeNull();
    expect(s().indexStale).toBe(false);
  });

  it("setRepoId(n) selects a repo and resets editor state", () => {
    s().openFile("a.java");
    s().setRepoId(7);
    expect(s().repoId).toBe(7);
    expect(s().tabs).toEqual([]);
  });

  it("clearIndexStale unsets the flag", () => {
    s().markIndexStale();
    s().clearIndexStale();
    expect(s().indexStale).toBe(false);
  });

  it("refreshTree bumps treeRefreshNonce", () => {
    const n = s().treeRefreshNonce;
    s().refreshTree();
    expect(s().treeRefreshNonce).toBe(n + 1);
  });

  it("requestNewChat / focusChat / requestSave bump their nonces", () => {
    const nc = s().newChatNonce, fc = s().focusChatNonce, sv = s().saveNonce;
    s().requestNewChat();
    s().focusChat();
    s().requestSave();
    expect(s().newChatNonce).toBe(nc + 1);
    expect(s().focusChatNonce).toBe(fc + 1);
    expect(s().saveNonce).toBe(sv + 1);
  });

  it("requestImportFolder bumps nonce and reveals the explorer", () => {
    s().setSidebarMode("search");
    const n = s().importFolderNonce;
    s().requestImportFolder();
    expect(s().importFolderNonce).toBe(n + 1);
    expect(s().sidebarMode).toBe("explorer");
    expect(s().view).toBe("editor");
  });

  it("requestGitImport bumps nonce and reveals the explorer", () => {
    const n = s().gitImportNonce;
    s().requestGitImport();
    expect(s().gitImportNonce).toBe(n + 1);
    expect(s().sidebarMode).toBe("explorer");
  });
});

// ─────────────────────────────────────────────────────────────
//  CC-6: openRequirementInsight / clearActiveRequirementId
// ─────────────────────────────────────────────────────────────

describe("openRequirementInsight", () => {
  it("sets view to requirements and activeRequirementId", () => {
    s().setView("editor");
    s().openRequirementInsight(42);
    expect(s().view).toBe("requirements");
    expect(s().activeRequirementId).toBe(42);
  });

  it("clears activeRequirementId after clearActiveRequirementId", () => {
    s().openRequirementInsight(7);
    expect(s().activeRequirementId).toBe(7);
    s().clearActiveRequirementId();
    expect(s().activeRequirementId).toBeUndefined();
  });

  it("does not affect other store fields", () => {
    useWorkbench.setState({ repoId: 99 });
    s().openRequirementInsight(5);
    expect(s().repoId).toBe(99);
  });
});

// ─────────────────────────────────────────────────────────────
//  C3: editorCollapsed + openFile auto-expand
// ─────────────────────────────────────────────────────────────

describe("editorCollapsed", () => {
  it("defaults to false", () => {
    expect(s().editorCollapsed).toBe(false);
  });

  it("setEditorCollapsed(true) collapses the editor", () => {
    s().setEditorCollapsed(true);
    expect(s().editorCollapsed).toBe(true);
  });

  it("setEditorCollapsed(false) expands the editor", () => {
    s().setEditorCollapsed(true);
    s().setEditorCollapsed(false);
    expect(s().editorCollapsed).toBe(false);
  });

  it("setEditorCollapsed is idempotent — calling true twice stays true", () => {
    s().setEditorCollapsed(true);
    s().setEditorCollapsed(true);
    expect(s().editorCollapsed).toBe(true);
  });

  it("openFile auto-expands when editorCollapsed is true", () => {
    s().setEditorCollapsed(true);
    s().openFile("src/Main.java");
    expect(s().editorCollapsed).toBe(false);
    expect(s().activePath).toBe("src/Main.java");
    expect(s().view).toBe("editor");
  });

  it("openFile does not change editorCollapsed when already expanded", () => {
    expect(s().editorCollapsed).toBe(false);
    s().openFile("src/Foo.java");
    expect(s().editorCollapsed).toBe(false);
  });

  it("openFile with line also auto-expands", () => {
    s().setEditorCollapsed(true);
    s().openFile("src/Bar.java", 42);
    expect(s().editorCollapsed).toBe(false);
    expect(s().revealTarget).toMatchObject({ path: "src/Bar.java", line: 42 });
  });
});
