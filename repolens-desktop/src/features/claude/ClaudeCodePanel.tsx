import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import { Panel, PanelGroup, PanelResizeHandle } from "react-resizable-panels";
import { Terminal } from "@xterm/xterm";
import { FitAddon } from "@xterm/addon-fit";
import "@xterm/xterm/css/xterm.css";
import { useClaudeStore } from "../../state/claudeStore";
import { useWorkbench } from "../../state/workbenchStore";
import { disposeModelFor } from "../editor/models";
import { classifyChangedFiles } from "./watchLogic";
import {
  makeAccumulator,
  debounceAccumulate,
  toRelativePaths,
  type DebounceAccumulator,
} from "./externalChangesFlow";
import {
  detectGuidanceKindWithinWindow,
  guidanceMessage,
  type GuidanceKind,
} from "./claudeGuidanceDetect";
import { externalChangesSummarize } from "../../api/requirementApi";
import { fetchMcpToken } from "../../api/mcpApi";
import { listBindings } from "../../api/feishuApi";
import { createFeishuReporter } from "./feishuOutputReporter";
import { ClaudeProjectSwitcher } from "./ClaudeProjectSwitcher";
import { findLiveSessionByDir } from "./poolLogic";
import { useI18n } from "../../i18n/I18nProvider";
import "./claude.css";

const IS_TAURI = "__TAURI_INTERNALS__" in window;

// ─────────────────────────────────────────────────────────────
//  Per-project terminal entry
// ─────────────────────────────────────────────────────────────

interface TermEntry {
  term: Terminal;
  fit: FitAddon;
  /** The div appended into wrapperRef — shown/hidden to switch projects. */
  container: HTMLDivElement;
  unlisten: (() => void) | null;
  ro: ResizeObserver | null;
  /** Feishu uplink reporter — forwards PTY output chunks to the backend when a CONNECTED binding exists. */
  reporter: { report: (chunk: string) => void };
}

// ─────────────────────────────────────────────────────────────
//  ClaudeCodePanel — multi-buffer, one xterm per live project
// ─────────────────────────────────────────────────────────────

/**
 * ClaudeCodePanel — embeds the real `claude` CLI TUI in a PTY terminal.
 *
 * Multi-buffer behaviour:
 *   - Each live project gets its own xterm instance (hidden div in the terminal wrapper).
 *   - Switching projects = toggle display:flex/none — no re-spawn, scrollback preserved.
 *   - Evicted sessions (ptyId cleared by the store) have their xterm disposed.
 *
 * §3c.2 split layout:
 *   - layout "single": classic single-pane, shows activeRepoId's xterm.
 *   - layout "split-2": two panes side-by-side; each pane body hosts one live xterm.
 *     Containers are moved between the limbo div (wrapperRef) and pane body divs
 *     (paneBodyRefs) on layout/visibility changes. No PTY is re-spawned.
 *
 * Backward compat:
 *   - CC-1's single-repo PTY path is preserved; the store's activeRepoId drives display.
 *   - Privacy notice shown per active repo; per-repo dismissal stored in localStorage.
 *   - Remote/snapshot repos display a disabled message via ClaudeProjectSwitcher.
 */
export function ClaudeCodePanel() {
  // Non-Tauri guard — must be checked before any xterm logic.
  if (!IS_TAURI) {
    return (
      <div className="claude-panel claude-panel--disabled">
        Claude Code 仅在桌面 App 可用（需通过 tauri dev 运行）
      </div>
    );
  }

  return <ClaudeCodePanelInner />;
}

function ClaudeCodePanelInner() {
  const { t } = useI18n();
  const activeRepoId = useClaudeStore((s) => s.activeRepoId);
  const sessions = useClaudeStore((s) => s.sessions);
  const prepareActivate = useClaudeStore((s) => s.prepareActivate);
  const markDormant = useClaudeStore((s) => s.markDormant);
  const setStatus = useClaudeStore((s) => s.setStatus);
  const setActiveRepoId = useClaudeStore((s) => s.setActiveRepoId);
  const isPrivacyDismissed = useClaudeStore((s) => s.isPrivacyDismissed);
  const dismissPrivacy = useClaudeStore((s) => s.dismissPrivacy);
  const feishuBindings = useClaudeStore((s) => s.feishuBindings);

  // §3c.2 layout state
  const layout = useClaudeStore((s) => s.layout);
  const visiblePtyIds = useClaudeStore((s) => s.visiblePtyIds);
  const setLayout = useClaudeStore((s) => s.setLayout);
  const setVisible = useClaudeStore((s) => s.setVisible);

  // CC-3 workbench actions for file-change propagation.
  const refreshTree = useWorkbench((s) => s.refreshTree);
  const markIndexStale = useWorkbench((s) => s.markIndexStale);
  const bumpFileReload = useWorkbench((s) => s.bumpFileReload);
  const setConflictPaths = useWorkbench((s) => s.setConflictPaths);
  const repoId = useWorkbench((s) => s.repoId);
  const rightEngine = useWorkbench((s) => s.rightEngine);

  // ── T3: Guidance banner (not-installed / not-logged-in) ──────
  const [guidanceKind, setGuidanceKind] = useState<GuidanceKind>(null);

  // ── T3: MCP not-connected hint ───────────────────────────────
  const [mcpConnected, setMcpConnected] = useState<boolean | null>(null); // null = unknown


  // ── T2: Debounce accumulator for external-changes flow ───────
  const extAccRef = useRef<DebounceAccumulator>(makeAccumulator());

  // Track which repos have had their privacy notice dismissed this session.
  // React state so the UI re-renders immediately after dismiss.
  const [dismissedSet, setDismissedSet] = useState<ReadonlySet<number>>(() => {
    // Seed from localStorage on first render.
    const s = new Set<number>();
    if (typeof localStorage !== "undefined") {
      for (let i = 0; i < localStorage.length; i++) {
        const k = localStorage.key(i);
        if (k?.startsWith("repolens.claudePrivacyDismissed.") && localStorage.getItem(k) === "true") {
          s.add(Number(k.split(".").pop()));
        }
      }
    }
    return s;
  });

  const showPrivacy =
    activeRepoId != null &&
    !dismissedSet.has(activeRepoId) &&
    !isPrivacyDismissed(activeRepoId);

  function handleDismissPrivacy() {
    if (activeRepoId == null) return;
    dismissPrivacy(activeRepoId);
    setDismissedSet((prev) => new Set([...prev, activeRepoId]));
  }

  // ── Terminal wiring ──────────────────────────────────────────

  /** Limbo wrapper: all xterm containers live here except when moved to a pane body. */
  const wrapperRef = useRef<HTMLDivElement>(null);
  /** Map from repoId → { term, fit, container, … } */
  const termMapRef = useRef<Map<number, TermEntry>>(new Map());
  /**
   * §3c.2 — refs to pane body divs rendered by React in split-2 mode.
   * Keyed by repoId; populated via ref callbacks, cleaned up on unmount.
   * applyContainerLayout moves visible containers into these divs.
   */
  const paneBodyRefs = useRef<Map<number, HTMLDivElement>>(new Map());
  /**
   * Debounce timers for the running → live transition.
   * Stored outside React state to avoid triggering re-renders.
   * Keyed by repoId; cleared when a session is disposed.
   */
  const runningTimersRef = useRef<Map<number, ReturnType<typeof setTimeout>>>(new Map());
  /**
   * Spawn timestamps (ms) recorded just before each `term_spawn` call.
   * Used to gate guidance-keyword detection to the startup window only.
   * Keyed by repoId; cleared when a session is disposed.
   */
  const spawnTimestampRef = useRef<Map<number, number>>(new Map());

  // ── Mount reconciliation: 上次卸载（切到 AI 会话再切回）时 xterm 已被 dispose，
  // 但 store 里的 session.ptyId 还留着 → prepareActivate 会误判「已活跃不用 spawn」→
  // 点击后一片空白。挂载时把残留 PTY 杀掉并清 ptyId，保证每次点击都重新 spawn 干净会话。
  useEffect(() => {
    const st = useClaudeStore.getState();
    // 挂载时 termMapRef 本就为空——凡 store 里还带 ptyId 的会话都是上次遗留的僵尸。
    const stale = Object.entries(st.sessions)
      .filter(([, s]) => s.ptyId != null)
      .map(([id, s]) => ({ repoId: Number(id), ptyId: s.ptyId! }));
    if (stale.length === 0) return;
    void import("@tauri-apps/api/core").then(({ invoke }) => {
      for (const { repoId, ptyId } of stale) {
        void invoke("term_kill", { id: ptyId }).catch(() => {});
        void invoke("repo_watch_stop", { watchId: repoId }).catch(() => {});
        st.markDormant(repoId);
      }
    });
    // 仅挂载时执行一次。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ─────────────────────────────────────────────────────────────
  //  §3c.2: applyContainerLayout — layout-aware show/hide/move
  // ─────────────────────────────────────────────────────────────

  /**
   * Sync every xterm container's DOM position and visibility with the
   * current layout + visiblePtyIds from the store.
   *
   * Reads the store directly (not subscribed values) so it can be called
   * from async handlers (doActivate) where subscribed values may be stale.
   *
   * "single" mode:
   *   All containers live in wrapperRef; only activeRepoId's is visible.
   *
   * "split-2" mode:
   *   Visible containers are moved into the corresponding pane body divs
   *   (paneBodyRefs); non-visible ones are moved to wrapperRef and hidden.
   *   Moving an existing DOM node to a new parent is non-destructive:
   *   xterm's canvas stays, ResizeObserver fires on the container and
   *   triggers fit() + term_resize automatically.
   */
  const applyContainerLayout = useCallback(() => {
    const { layout, visiblePtyIds, activeRepoId } = useClaudeStore.getState();
    termMapRef.current.forEach((entry, repoId) => {
      if (layout === "single") {
        // Move container to limbo wrapper (if not already there).
        if (wrapperRef.current && entry.container.parentElement !== wrapperRef.current) {
          wrapperRef.current.appendChild(entry.container);
        }
        entry.container.style.display = repoId === activeRepoId ? "flex" : "none";
        if (repoId === activeRepoId) {
          try { entry.fit.fit(); } catch { /* xterm not yet ready */ }
        }
      } else {
        // split-2: visible → pane body; non-visible → limbo (hidden).
        const paneBody = paneBodyRefs.current.get(repoId);
        if (paneBody && visiblePtyIds.includes(repoId)) {
          if (entry.container.parentElement !== paneBody) {
            paneBody.appendChild(entry.container);
          }
          entry.container.style.display = "flex";
          try { entry.fit.fit(); } catch { /* xterm not yet ready */ }
        } else {
          if (wrapperRef.current && entry.container.parentElement !== wrapperRef.current) {
            wrapperRef.current.appendChild(entry.container);
          }
          entry.container.style.display = "none";
        }
      }
    });
  }, []); // Stable — reads from refs/store directly.

  // ── Layout effect: sync containers on layout/visibility change ──
  // useLayoutEffect runs synchronously before paint, preventing a flash
  // when switching between single and split-2.
  useLayoutEffect(() => {
    applyContainerLayout();
  }, [layout, visiblePtyIds, activeRepoId, applyContainerLayout]);

  // ── Dispose xterm entries for evicted sessions ───────────────
  useEffect(() => {
    const toEvict: number[] = [];
    termMapRef.current.forEach((_, repoId) => {
      const s = sessions[repoId];
      if (!s || s.ptyId == null) toEvict.push(repoId);
    });
    for (const repoId of toEvict) {
      disposeEntry(repoId);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessions]);

  function disposeEntry(repoId: number) {
    // Clear any pending running → live transition timer.
    const timer = runningTimersRef.current.get(repoId);
    if (timer != null) {
      clearTimeout(timer);
      runningTimersRef.current.delete(repoId);
    }
    // Clear spawn timestamp to avoid stale state if the session is re-opened.
    spawnTimestampRef.current.delete(repoId);
    const entry = termMapRef.current.get(repoId);
    if (!entry) return;
    entry.ro?.disconnect();
    entry.unlisten?.();
    try { entry.term.dispose(); } catch { /* already disposed */ }
    entry.container.remove();
    termMapRef.current.delete(repoId);
  }

  // ── Unmount cleanup ─────────────────────────────────────────
  useEffect(() => {
    return () => {
      // Dispose all xterm instances on panel unmount.
      // We don't kill PTYs here — they stay alive in the Rust side
      // for the session lifetime; Rust frees them when the app exits.
      termMapRef.current.forEach((_, repoId) => disposeEntry(repoId));
      // Stop all active file watchers.
      import("@tauri-apps/api/core").then(({ invoke }) => {
        termMapRef.current.forEach((_, id) => {
          void invoke("repo_watch_stop", { watchId: id }).catch(() => {});
        });
      }).catch(() => {});
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ── T3: Check MCP connectivity on mount / engine switch ─────
  // Self-heals: if disconnected, polls every 5 s until connected.
  // Stops on success, on unmount, or when engine is switched away.
  useEffect(() => {
    if (rightEngine !== "claude") {
      // Engine switched away — reset so the banner clears; timer cleaned below.
      setMcpConnected(null);
      return;
    }

    let cancelled = false;
    let timerId: ReturnType<typeof setTimeout> | undefined;

    async function probe() {
      try {
        await fetchMcpToken();
        if (!cancelled) setMcpConnected(true);
        // Connected — stop polling.
      } catch {
        if (!cancelled) {
          setMcpConnected(false);
          // Schedule next probe in 5 s only if still disconnected.
          timerId = setTimeout(probe, 5000);
        }
      }
    }

    void probe();

    return () => {
      cancelled = true;
      if (timerId != null) clearTimeout(timerId);
    };
  }, [rightEngine]);

  // ── CC-3 + T2: Subscribe to repo-file-changed events ────────
  useEffect(() => {
    if (!IS_TAURI) return;
    let unlistenFn: (() => void) | null = null;
    const acc = extAccRef.current;

    import("@tauri-apps/api/event").then(({ listen }) => {
      listen<{ watchId: number; paths: string[] }>(
        "repo-file-changed",
        (event) => {
          const { paths: changedPaths } = event.payload;

          // Always bump tree + mark index stale.
          refreshTree();
          markIndexStale();

          // Classify which open tabs need reload vs. conflict warning.
          const activeSession = activeRepoId != null
            ? useClaudeStore.getState().sessions[activeRepoId]
            : null;
          const realDir = activeSession?.realDir ?? "";
          if (!realDir) return;

          const wb = useWorkbench.getState();
          const openTabs = wb.tabs; // { path, dirty }[]
          const { toReload, toConflict } = classifyChangedFiles(
            changedPaths,
            openTabs,
            realDir,
          );

          // Clear cache + signal MonacoEditor to re-fetch for clean files.
          for (const relPath of toReload) {
            if (repoId != null) disposeModelFor(repoId, relPath);
            bumpFileReload(relPath);
          }

          // Surface conflict paths for the editor to show a warning banner.
          if (toConflict.length > 0) {
            setConflictPaths([...wb.conflictPaths, ...toConflict]);
          }

          // T2: When engine=claude, debounce-aggregate changed paths →
          // call external-changes/summarize → open requirement insight card.
          const currentEngine = useWorkbench.getState().rightEngine;
          const currentRepoId = useWorkbench.getState().repoId;
          if (currentEngine === "claude" && currentRepoId != null) {
            const relPaths = toRelativePaths(changedPaths, realDir);
            debounceAccumulate(acc, relPaths, 3000, (paths) => {
              if (paths.length === 0) return;
              void externalChangesSummarize(currentRepoId, paths, realDir).then(
                (reqId) => {
                  if (reqId != null) {
                    // B1: server may return the same reqId if the burst was merged into
                    // an existing requirement. openRequirementInsight handles both new
                    // open and re-open gracefully; the wider debounce (3 s) reduces
                    // the frequency of re-opens for the same merged requirement.
                    useWorkbench.getState().openRequirementInsight(reqId);
                  }
                },
              ).catch(() => {
                // Fail-safe: summarization error must not crash the panel.
              });
            });
          }
        },
      ).then((fn) => {
        unlistenFn = fn;
      }).catch(() => {});
    }).catch(() => {});

    return () => {
      unlistenFn?.();
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeRepoId, repoId, refreshTree, markIndexStale, bumpFileReload, setConflictPaths]);

  // ── Inner activate — shared by handleActivate + confirmSwitch ─
  const doActivate = useCallback(
    async (repoId: number, realDir: string) => {
      // C1: If another live session already occupies the same realDir, reuse it
      // instead of spawning a second `claude` process in the same working dir.
      const liveRepoId = findLiveSessionByDir(
        useClaudeStore.getState().sessions,
        realDir,
      );
      // 仅当那个同目录会话确有一个真实存活的终端（termMapRef 里有）才复用；
      // 否则（终端已被 dispose 只剩 store 残留）走正常 spawn，避免复用到空壳。
      if (liveRepoId != null && liveRepoId !== repoId && termMapRef.current.has(liveRepoId)) {
        setActiveRepoId(liveRepoId);
        // §3c.2: applyContainerLayout handles both single and split-2.
        applyContainerLayout();
        return;
      }

      // Step 1: compute plan and apply state update (synchronous).
      const plan = prepareActivate(repoId, realDir);

      // Step 2: kill evicted PTY (+ stop its file watcher) if any.
      if (plan.evictRepoId != null && plan.evictPtyId != null) {
        try {
          const { invoke } = await import("@tauri-apps/api/core");
          await invoke("term_kill", { id: plan.evictPtyId });
          // CC-3: stop file watcher for the evicted project.
          void invoke("repo_watch_stop", { watchId: plan.evictRepoId }).catch(() => {});
        } catch { /* PTY may already be dead */ }
        markDormant(plan.evictRepoId);
        // disposeEntry is triggered by the sessions effect when ptyId clears.
      }

      // Step 3: spawn a new PTY if needed.
      // 兜底：store 认为已活跃(needsSpawn=false)但真实终端不在 termMapRef（上次卸载被
      // dispose 只剩残留 ptyId）→ 先杀掉遗留 PTY 再当作需要重新 spawn，避免点击后空白。
      let needsSpawn = plan.needsSpawn;
      if (!needsSpawn && !termMapRef.current.has(repoId)) {
        try {
          const { invoke } = await import("@tauri-apps/api/core");
          await invoke("term_kill", { id: plan.ptyId }).catch(() => {});
        } catch { /* ignore */ }
        needsSpawn = true;
      }
      if (needsSpawn && wrapperRef.current) {
        await spawnTerminal(repoId, realDir, plan.ptyId, plan.spawnArgs);
        // CC-3: start file watcher for the newly activated project.
        try {
          const { invoke } = await import("@tauri-apps/api/core");
          void invoke("repo_watch_start", { watchId: repoId, path: realDir }).catch(() => {});
        } catch { /* non-fatal: watcher is best-effort */ }
      }

      // Step 4: reveal the now-active container (layout-aware).
      // applyContainerLayout reads fresh store state (updated in Step 1) and
      // moves the container to the correct location for the current layout.
      applyContainerLayout();
    },
    [prepareActivate, markDormant, setActiveRepoId, applyContainerLayout],
  );


  // ── Activate handler (called from ClaudeProjectSwitcher) ────
  const handleActivate = useCallback(
    async (repoId: number, realDir: string) => {
      // 切换不会终止运行中的任务（保活池保护），无需二次确认——直接切。
      await doActivate(repoId, realDir);
    },
    [doActivate],
  );

  // ── §3c.2: Layout toggle handler ────────────────────────────
  function handleSetLayout(l: "single" | "split-2") {
    setLayout(l);
    // The useLayoutEffect will fire after the store update and re-render,
    // calling applyContainerLayout to move containers to/from pane bodies.
  }

  // ── §3c.2: Close a pane in split-2 mode ─────────────────────
  function handleClosePane(closedRepoId: number) {
    const newVisible = visiblePtyIds.filter((id) => id !== closedRepoId);
    if (newVisible.length <= 1) {
      // Fewer than 2 panes remain → fall back to single.
      // If the closed pane was active, switch to the remaining one.
      if (closedRepoId === activeRepoId && newVisible.length === 1) {
        setActiveRepoId(newVisible[0]);
        // setActiveRepoId above updates activeRepoId; setLayout("single") below
        // reads the updated activeRepoId via Zustand's synchronous set.
      }
      setLayout("single");
    } else {
      setVisible(newVisible);
    }
  }

  async function spawnTerminal(
    repoId: number,
    realDir: string,
    ptyId: number,
    spawnArgs: string[],
  ) {
    if (!wrapperRef.current) return;

    // Create a new container div for this project's xterm.
    // It starts in the limbo wrapper (wrapperRef); applyContainerLayout will
    // move it to the appropriate pane body if layout === "split-2".
    const container = document.createElement("div");
    container.className = "claude-terminal-body";
    container.style.display = "none"; // revealed after spawn
    wrapperRef.current.appendChild(container);

    const term = new Terminal({
      fontSize: 12.5,
      fontFamily: "Menlo, monospace",
      theme: { background: "#181818", foreground: "#cccccc" },
    });
    const fit = new FitAddon();
    term.loadAddon(fit);
    term.open(container);
    fit.fit();

    // Feishu uplink reporter — reads bindings from store (populated lazily below).
    const reporter = createFeishuReporter(repoId, () => {
      return useClaudeStore.getState().feishuBindings[repoId] ?? [];
    });

    const entry: TermEntry = { term, fit, container, unlisten: null, ro: null, reporter };
    termMapRef.current.set(repoId, entry);

    const { invoke } = await import("@tauri-apps/api/core");
    const { listen } = await import("@tauri-apps/api/event");

    // Listen for PTY output before spawning to avoid losing early bytes.
    const unlisten = await listen<string>(`term-out-${ptyId}`, (e) => {
      term.write(e.payload);
      // Mark session "running" while output is flowing; debounce back to "live"
      // after 12 s of idle output so keep-alive protection becomes functional.
      setStatus(repoId, "running");
      const prev = runningTimersRef.current.get(repoId);
      if (prev != null) clearTimeout(prev);
      const t = setTimeout(() => {
        setStatus(repoId, "live");
        runningTimersRef.current.delete(repoId);
      }, 12_000);
      runningTimersRef.current.set(repoId, t);

      // T3: Scan PTY output for not-installed / not-logged-in signals.
      // Only scan during the startup window (first 5 s after spawn) to avoid
      // false-positive banners from "command not found" / "No such file or
      // directory" output produced by arbitrary shell commands run later.
      // Only show guidance once (when guidanceKind is still null).
      setGuidanceKind((prev) => {
        if (prev != null) return prev; // already showing guidance
        const spawnTime = spawnTimestampRef.current.get(repoId) ?? Infinity;
        return detectGuidanceKindWithinWindow(e.payload, spawnTime);
      });

      // Feishu uplink: forward PTY output to Feishu if a CONNECTED binding exists.
      // Fire-and-forget; throttled at 500 ms inside the reporter.
      entry.reporter.report(e.payload);
    });
    entry.unlisten = unlisten;

    // Guard: component may have unmounted or repo evicted while listen() was in-flight.
    if (!termMapRef.current.has(repoId)) {
      unlisten();
      try { term.dispose(); } catch { /* ignore */ }
      container.remove();
      return;
    }

    // Record the spawn timestamp before the PTY starts so the guidance-keyword
    // scanner can gate itself to the startup window only.
    spawnTimestampRef.current.set(repoId, Date.now());

    // Populate Feishu bindings in the store so the uplink reporter can check
    // CONNECTED status without waiting for the user to open the binding panel.
    listBindings(repoId)
      .then((bindings) => {
        useClaudeStore.getState().setFeishuBindings(repoId, bindings);
      })
      .catch(() => {
        // Non-fatal — reporter silently skips if no bindings in store.
      });

    // Spawn the claude CLI.
    await invoke("term_spawn", {
      id: ptyId,
      cwd: realDir,
      program: "claude",
      args: spawnArgs,
      cols: term.cols,
      rows: term.rows,
    });

    // Guard: component may have unmounted or repo evicted while term_spawn() was in-flight.
    // Kill the just-started PTY to avoid an orphaned process.
    if (!termMapRef.current.has(repoId)) {
      unlisten();
      try { term.dispose(); } catch { /* ignore */ }
      container.remove();
      try { await invoke("term_kill", { id: ptyId }); } catch { /* PTY may not have fully started */ }
      return;
    }

    // Wire keyboard input → PTY.
    term.onData((data: string) => {
      void invoke("term_write", { id: ptyId, data });
    });

    // Keep terminal sized to its container.
    const ro = new ResizeObserver(() => {
      try {
        fit.fit();
        void invoke("term_resize", { id: ptyId, cols: term.cols, rows: term.rows });
      } catch { /* container may have been removed */ }
    });
    ro.observe(container);
    entry.ro = ro;
  }

  // ─────────────────────────────────────────────────────────────
  //  Render
  // ─────────────────────────────────────────────────────────────

  const guidanceMsg = guidanceMessage(guidanceKind);

  return (
    <div className="claude-panel">
      {/* T3: Not-installed / not-logged-in guidance banner */}
      {guidanceMsg != null && (
        <div className="claude-guidance-bar">
          <span>{guidanceMsg}</span>
          <button
            className="claude-guidance-dismiss"
            onClick={() => setGuidanceKind(null)}
            aria-label="关闭"
          >
            ✕
          </button>
        </div>
      )}

      {/* Privacy notice for the active project */}
      {showPrivacy && (
        <div className="claude-privacy-bar">
          ⚠ Claude Code 会将代码发送到 Anthropic 云端（区别于本地隐私模式）
          <button
            className="claude-privacy-dismiss"
            onClick={handleDismissPrivacy}
          >
            知道了
          </button>
        </div>
      )}

      {/* T3: MCP not-connected hint */}
      {mcpConnected === false && (
        <div className="claude-mcp-hint">
          app 能力未连上（MCP server 未响应）。Claude Code 仍可作纯终端使用；数据工具不可用。
        </div>
      )}

      {/* Main content: project switcher + terminal area (C2: draggable border) */}
      <PanelGroup
        direction="horizontal"
        className="claude-body"
        autoSaveId="repolens-claude-h"
      >
        {/* Left sidebar: project list */}
        <Panel defaultSize={22} minSize={8}>
          <div className="claude-sidebar">
            <ClaudeProjectSwitcher onActivate={handleActivate} />
          </div>
        </Panel>

        <PanelResizeHandle className="resize-handle-v" />

        {/* Terminal area — layout-aware (§3c.2) */}
        <Panel minSize={20}>
          <div className="claude-terminal-area">
            {/* §3c.2: Layout toggle bar */}
            <div className="claude-layout-bar">
              <span className="claude-layout-bar-label">{t("claude.view", "视图")}</span>
              <button
                className={`claude-layout-btn${layout === "single" ? " claude-layout-btn--active" : ""}`}
                onClick={() => handleSetLayout("single")}
                title={t("claude.single", "单窗格")}
                aria-label={t("claude.single", "单窗格")}
              >
                ⬛
              </button>
              <button
                className={`claude-layout-btn${layout === "split-2" ? " claude-layout-btn--active" : ""}`}
                onClick={() => handleSetLayout("split-2")}
                title={t("claude.split", "双窗格")}
                aria-label={t("claude.split", "双窗格")}
              >
                ▥
              </button>
            </div>

            {/* Single mode: placeholder when no active project */}
            {layout === "single" && activeRepoId == null && (
              <div className="claude-terminal-placeholder">
                ← 从左侧选择一个本地项目启动 Claude Code
              </div>
            )}

            {/* §3c.2: Split-2 pane structure */}
            {layout === "split-2" && (
              <div className="claude-split-container">
                {visiblePtyIds.length === 0 ? (
                  <div className="claude-terminal-placeholder">
                    {t("claude.pickProject", "← 从左侧选择一个本地项目启动 Claude Code")}
                  </div>
                ) : (
                  visiblePtyIds.map((paneRepoId) => {
                    const s = sessions[paneRepoId];
                    // Derive display name from realDir (last path segment).
                    const name = s?.realDir?.split("/").pop() ?? String(paneRepoId);
                    const hasFeishu = (feishuBindings[paneRepoId] ?? []).some(
                      (b) => b.status === "CONNECTED",
                    );
                    return (
                      <div key={paneRepoId} className="claude-pane">
                        {/* Pane title bar: project name + status dot + feishu badge + close */}
                        <div className="claude-pane-header">
                          <span
                            className={`claude-pane-status-dot claude-pane-status-dot--${s?.status ?? "dormant"}`}
                            title={s?.status ?? "dormant"}
                          />
                          <span className="claude-pane-name" title={s?.realDir}>
                            {name}
                          </span>
                          {hasFeishu && (
                            <span className="claude-pane-feishu" title="飞书已绑定">
                              🔗
                            </span>
                          )}
                          <button
                            className="claude-pane-close"
                            onClick={() => handleClosePane(paneRepoId)}
                            title="关闭窗格"
                            aria-label="关闭窗格"
                          >
                            ✕
                          </button>
                        </div>
                        {/* Pane body — xterm container is moved here by applyContainerLayout */}
                        <div
                          className="claude-pane-body"
                          ref={(el) => {
                            if (el) {
                              paneBodyRefs.current.set(paneRepoId, el);
                            } else {
                              paneBodyRefs.current.delete(paneRepoId);
                            }
                          }}
                        />
                      </div>
                    );
                  })
                )}
              </div>
            )}

            {/* Always-present limbo wrapper — holds all xterm containers not currently
                displayed in a pane body. In single mode this IS the visible terminal area;
                in split-2 mode it is hidden (containers are moved to pane bodies). */}
            <div
              className="claude-terminal-wrapper"
              ref={wrapperRef}
              style={layout === "split-2" ? { display: "none" } : undefined}
            />
          </div>
        </Panel>
      </PanelGroup>

      {/* Install / login hint (static, always visible) */}
      <div className="claude-install-hint">
        需要已安装并登录 Claude Code CLI：
        <code>npm i -g @anthropic-ai/claude-code</code> 后运行{" "}
        <code>claude</code> 完成登录
      </div>
    </div>
  );
}
