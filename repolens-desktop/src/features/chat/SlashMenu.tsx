import { useEffect, useRef } from "react";
import { SlashItem } from "./slashUtils";

interface Props {
  items: SlashItem[];
  activeIndex: number;
  onSelect: (item: SlashItem) => void;
  onClose: () => void;
  loading?: boolean;
}

/**
 * `/` 斜杠命令面板（对齐 Claude Code 的 /command 菜单）。展示可用 skill / 自定义命令的
 * 名字 + 一句话说明 + 来源徽标；上下键选、Enter/Tab 确认、Esc 关、点击选中。
 * 结构复用 mention-menu 的定位/滚动/外点关闭，另加描述行与徽标。
 */
export function SlashMenu({ items, activeIndex, onSelect, onClose, loading }: Props) {
  const menuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!menuRef.current) return;
    const active = menuRef.current.querySelector(".slash-menu-item.active");
    if (active) active.scrollIntoView({ block: "nearest" });
  }, [activeIndex]);

  useEffect(() => {
    function onMouseDown(e: MouseEvent) {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        onClose();
      }
    }
    document.addEventListener("mousedown", onMouseDown);
    return () => document.removeEventListener("mousedown", onMouseDown);
  }, [onClose]);

  return (
    <div className="mention-menu slash-menu" ref={menuRef}>
      <div className="slash-menu-head">/ 斜杠命令 · 输入筛选 · ↑↓ 选 · Enter 确认 · Esc 关</div>
      {loading && items.length === 0 ? (
        <div className="mention-menu-empty">加载中…</div>
      ) : !loading && items.length === 0 ? (
        <div className="mention-menu-empty">没有匹配的 skill / 命令</div>
      ) : (
        items.map((item, index) => (
          <div
            key={`${item.type}:${item.name}`}
            className={`slash-menu-item${index === activeIndex ? " active" : ""}`}
            onMouseDown={(e) => {
              // mousedown fires before textarea blur
              e.preventDefault();
              onSelect(item);
            }}
          >
            <div className="slash-menu-row">
              <span className="slash-menu-name">/{item.name}</span>
              <span className={`slash-menu-badge slash-${item.type}`}>
                {item.type === "skill"
                  ? item.source === "project"
                    ? "项目 skill"
                    : item.source === "personal"
                    ? "个人 skill"
                    : "skill"
                  : "命令"}
              </span>
            </div>
            <div className="slash-menu-desc">{item.description}</div>
          </div>
        ))
      )}
    </div>
  );
}
