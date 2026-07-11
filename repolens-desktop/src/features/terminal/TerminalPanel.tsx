import { useEffect, useRef } from "react";
import { Terminal } from "@xterm/xterm";
import { FitAddon } from "@xterm/addon-fit";
import "@xterm/xterm/css/xterm.css";
import "./terminal.css";
import { useWorkbench } from "../../state/workbenchStore";

const IS_TAURI = "__TAURI_INTERNALS__" in window;
let nextId = 1;

export function TerminalPanel() {
  const toggleTerminal = useWorkbench((s) => s.toggleTerminal);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!IS_TAURI || !containerRef.current) return;
    const id = nextId++;
    const term = new Terminal({
      fontSize: 12.5,
      fontFamily: "Menlo, monospace",
      theme: { background: "#181818", foreground: "#cccccc" },
    });
    const fit = new FitAddon();
    term.loadAddon(fit);
    term.open(containerRef.current);
    fit.fit();

    let unlisten: (() => void) | null = null;
    let disposed = false;
    let ro: ResizeObserver | null = null;
    (async () => {
      const { invoke } = await import("@tauri-apps/api/core");
      const { listen } = await import("@tauri-apps/api/event");
      const un = await listen<string>(`term-out-${id}`, (e) => term.write(e.payload));
      if (disposed) { un(); return; }
      unlisten = un;
      await invoke("term_spawn", { id, cwd: null, cols: term.cols, rows: term.rows });
      term.onData((data) => { void invoke("term_write", { id, data }); });
      ro = new ResizeObserver(() => {
        if (disposed) return;
        fit.fit();
        void invoke("term_resize", { id, cols: term.cols, rows: term.rows });
      });
      ro.observe(containerRef.current!);
    })();

    return () => {
      disposed = true;
      ro?.disconnect();
      unlisten?.();
      void import("@tauri-apps/api/core").then(({ invoke }) => invoke("term_kill", { id }));
      term.dispose();
    };
  }, []);

  if (!IS_TAURI) {
    return (
      <div className="terminal-panel terminal-fallback">
        <span className="codicon codicon-terminal" /> 终端仅在桌面 App 窗口可用（npm run tauri dev）
      </div>
    );
  }
  return (
    <div className="terminal-panel">
      <div className="terminal-head">
        <span className="codicon codicon-terminal" /> 终端
        <span className="codicon codicon-close terminal-close" onClick={toggleTerminal} />
      </div>
      <div className="terminal-body" ref={containerRef} />
    </div>
  );
}
