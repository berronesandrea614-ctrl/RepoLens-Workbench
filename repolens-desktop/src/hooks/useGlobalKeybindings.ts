import { useEffect, useState } from "react";
import { useWorkbench } from "../state/workbenchStore";
import type { PaletteMode } from "../features/palette/CommandPalette";

/** Cmd+P 文件、Cmd+Shift+P 命令、Ctrl+` 终端、Cmd+\ 拆分编辑器；capture 阶段拦截浏览器默认行为。 */
export function useGlobalKeybindings() {
  const [palette, setPalette] = useState<PaletteMode | null>(null);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      const mod = e.metaKey || e.ctrlKey;
      if (mod && e.key.toLowerCase() === "p") {
        e.preventDefault();
        setPalette(e.shiftKey ? "commands" : "files");
      } else if (e.ctrlKey && e.key === "`") {
        e.preventDefault();
        useWorkbench.getState().toggleTerminal();
      } else if (mod && e.key === "\\") {
        e.preventDefault();
        useWorkbench.getState().splitEditor();
      } else if (mod && e.key.toLowerCase() === "s") {
        // Monaco 聚焦时由编辑器命令处理；这里兜底阻止浏览器"保存网页"
        if (!(e.target as HTMLElement)?.closest?.(".monaco-editor")) e.preventDefault();
      }
    }
    window.addEventListener("keydown", onKey, true);
    return () => window.removeEventListener("keydown", onKey, true);
  }, []);

  return { palette, closePalette: () => setPalette(null) };
}
