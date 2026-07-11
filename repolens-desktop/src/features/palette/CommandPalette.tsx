import { useEffect, useMemo, useRef, useState } from "react";
import { fetchTree } from "../../api/treeApi";
import { flattenTree } from "../explorer/FileTree";
import { fuzzyScore } from "../../ui/fuzzy";
import { iconForFile } from "../../ui/fileIcons";
import { useWorkbench } from "../../state/workbenchStore";
import { buildCommands } from "./commands";
import "./palette.css";

export type PaletteMode = "files" | "commands";

export function CommandPalette({ mode, onClose }: { mode: PaletteMode; onClose: () => void }) {
  const repoId = useWorkbench((s) => s.repoId);
  const openFile = useWorkbench((s) => s.openFile);
  const [input, setInput] = useState(mode === "commands" ? ">" : "");
  const [files, setFiles] = useState<string[]>([]);
  const [sel, setSel] = useState(0);
  const listRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (repoId == null) { setFiles([]); return; }
    fetchTree(repoId).then((t) => setFiles(flattenTree(t))).catch(() => setFiles([]));
  }, [repoId]);

  const isCommandMode = input.startsWith(">");
  const query = isCommandMode ? input.slice(1).trim() : input.trim();

  const items = useMemo(() => {
    if (isCommandMode) {
      return buildCommands()
        .map((c) => ({ key: c.id, label: c.title, score: fuzzyScore(query, c.title), run: c.run }))
        .filter((i) => i.score >= 0)
        .sort((a, b) => b.score - a.score)
        .slice(0, 30);
    }
    return files
      .map((f) => ({ key: f, label: f, score: fuzzyScore(query, f), run: () => openFile(f) }))
      .filter((i) => i.score >= 0)
      .sort((a, b) => b.score - a.score)
      .slice(0, 30);
  }, [isCommandMode, query, files, openFile]);

  useEffect(() => { setSel(0); }, [input]);

  function onKeyDown(e: React.KeyboardEvent) {
    if (e.key === "Escape") { e.preventDefault(); onClose(); }
    else if (e.key === "ArrowDown") { e.preventDefault(); setSel((s) => Math.min(s + 1, items.length - 1)); }
    else if (e.key === "ArrowUp") { e.preventDefault(); setSel((s) => Math.max(s - 1, 0)); }
    else if (e.key === "Enter") {
      e.preventDefault();
      const item = items[sel];
      if (item) { item.run(); onClose(); }
    }
  }

  useEffect(() => {
    listRef.current?.children[sel]?.scrollIntoView({ block: "nearest" });
  }, [sel]);

  return (
    <div className="palette-overlay" onMouseDown={onClose}>
      <div className="palette" onMouseDown={(e) => e.stopPropagation()}>
        <input autoFocus value={input} onChange={(e) => setInput(e.target.value)} onKeyDown={onKeyDown}
          placeholder="输入文件名；> 前缀执行命令" />
        <div className="palette-list" ref={listRef}>
          {items.map((it, i) => {
            const name = isCommandMode ? it.label : (it.label.split("/").pop() ?? it.label);
            const iconInfo = isCommandMode ? null : iconForFile(name);
            return (
              <div key={it.key} className={`palette-item ${i === sel ? "selected" : ""}`}
                onMouseEnter={() => setSel(i)}
                onClick={() => { it.run(); onClose(); }}>
                {iconInfo
                  ? <span className={`codicon ${iconInfo.icon}`} style={{ color: iconInfo.color }} />
                  : <span className="codicon codicon-run" />}
                <span className="palette-name">{name}</span>
                {!isCommandMode && <span className="palette-dir">{it.label}</span>}
              </div>
            );
          })}
          {items.length === 0 && <div className="palette-empty">无匹配结果</div>}
        </div>
      </div>
    </div>
  );
}
