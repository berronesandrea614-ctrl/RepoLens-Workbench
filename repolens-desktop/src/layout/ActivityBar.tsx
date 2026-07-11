import { useWorkbench } from "../state/workbenchStore";
import { useEffect, useRef, useState } from "react";
import { useI18n } from "../i18n/I18nProvider";
import type { MessageKey } from "../i18n/zh";

/** 可上下拖动排序的视图工具（顶部资源管理器/搜索、底部终端/编辑器/会话/设置保持固定）。 */
type ToolKey =
  | "graph" | "requirements" | "agentruns" | "debt" | "provenance"
  | "traceability" | "adr" | "sensitive" | "mission" | "timeline" | "drift" | "branch"
  | "egress";

// title/titleKey：title 为中文兜底（也是 t 的 fallback），titleKey 供 i18n 查表。
// titleKey 可选：尚未接入 i18n 词条的新工具只给 title，t() 用 view 名兜底即可。
const TOOLS: { view: ToolKey; title: string; titleKey?: MessageKey; icon: string }[] = [
  { view: "graph", title: "数据流转", titleKey: "tool.graph", icon: "codicon-type-hierarchy-sub" },
  { view: "requirements", title: "需求流", titleKey: "tool.requirements", icon: "codicon-checklist" },
  { view: "agentruns", title: "Agent 执行", titleKey: "tool.agentruns", icon: "codicon-pulse" },
  { view: "debt", title: "理解债务", titleKey: "tool.debt", icon: "codicon-warning" },
  { view: "provenance", title: "AI 贡献溯源", titleKey: "tool.provenance", icon: "codicon-verified" },
  { view: "traceability", title: "Spec↔实现追溯", titleKey: "tool.traceability", icon: "codicon-git-compare" },
  { view: "adr", title: "自动 ADR", titleKey: "tool.adr", icon: "codicon-notebook" },
  { view: "sensitive", title: "敏感文件", titleKey: "tool.sensitive", icon: "codicon-shield" },
  { view: "mission", title: "Mission Control", titleKey: "tool.mission", icon: "codicon-dashboard" },
  { view: "timeline", title: "时间轴", titleKey: "tool.timeline", icon: "codicon-history" },
  { view: "drift", title: "架构漂移", titleKey: "tool.drift", icon: "codicon-radio-tower" },
  { view: "branch", title: "方案分支", titleKey: "tool.branch", icon: "codicon-git-branch" },
  { view: "egress", title: "出网监控", icon: "codicon-globe" },
];

const ORDER_KEY = "repolens.activitybar.order";
const DRAG_THRESHOLD = 5; // px，超过才算拖动（否则视为点击）

/** 读持久化顺序：只保留已知项，末尾补上新增工具（前向兼容）。 */
function loadOrder(): ToolKey[] {
  const all = TOOLS.map((t) => t.view);
  try {
    const raw = localStorage.getItem(ORDER_KEY);
    if (raw) {
      const saved = (JSON.parse(raw) as ToolKey[]).filter((v) => all.includes(v));
      for (const v of all) if (!saved.includes(v)) saved.push(v);
      return saved;
    }
  } catch {
    /* ignore corrupt storage */
  }
  return all;
}

export function ActivityBar() {
  const { t } = useI18n();
  const view = useWorkbench((s) => s.view);
  const sidebarMode = useWorkbench((s) => s.sidebarMode);
  const setView = useWorkbench((s) => s.setView);
  const setSidebarMode = useWorkbench((s) => s.setSidebarMode);
  const toggleTerminal = useWorkbench((s) => s.toggleTerminal);
  const focusChat = useWorkbench((s) => s.focusChat);
  const editorCollapsed = useWorkbench((s) => s.editorCollapsed);
  const setEditorCollapsed = useWorkbench((s) => s.setEditorCollapsed);

  const [order, setOrder] = useState<ToolKey[]>(loadOrder);
  // 拖动状态：from=被拖项索引, over=将插入到的索引；null=未拖动。
  const [drag, setDrag] = useState<{ from: number; over: number } | null>(null);
  const activityRef = useRef<HTMLDivElement>(null);
  // 最新 order 的引用，供事件回调读取（避免闭包过期）。
  const orderRef = useRef(order);
  orderRef.current = order;

  useEffect(() => {
    try {
      localStorage.setItem(ORDER_KEY, JSON.stringify(order));
    } catch {
      /* ignore */
    }
  }, [order]);

  const explorerActive = view === "editor" && sidebarMode === "explorer";
  const searchActive = sidebarMode === "search";
  const toolByView = new Map(TOOLS.map((t) => [t.view, t]));

  /** 根据鼠标 Y 求应插入到第几个可排序项之前。 */
  function indexFromY(clientY: number): number {
    const root = activityRef.current;
    if (!root) return 0;
    const els = Array.from(root.querySelectorAll<HTMLElement>("[data-reorder]"));
    for (let i = 0; i < els.length; i++) {
      const r = els[i].getBoundingClientRect();
      if (clientY < r.top + r.height / 2) return i;
    }
    return els.length - 1;
  }

  /** 指针驱动的拖动排序（绕开 Tauri/WKWebView 对 HTML5 DnD 的拦截）。 */
  function startDrag(e: React.MouseEvent, fromIdx: number) {
    if (e.button !== 0) return;
    e.preventDefault();
    const startY = e.clientY;
    let moved = false;

    const onMove = (ev: MouseEvent) => {
      if (!moved && Math.abs(ev.clientY - startY) < DRAG_THRESHOLD) return;
      moved = true;
      setDrag({ from: fromIdx, over: indexFromY(ev.clientY) });
    };
    const onUp = (ev: MouseEvent) => {
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
      if (moved) {
        const over = indexFromY(ev.clientY);
        setOrder((prev) => {
          if (fromIdx === over) return prev;
          const next = [...prev];
          const [m] = next.splice(fromIdx, 1);
          next.splice(over, 0, m);
          return next;
        });
      } else {
        // 无位移 → 视为点击，切换视图
        setView(orderRef.current[fromIdx]);
      }
      setDrag(null);
    };
    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
  }

  return (
    <div className={`activity${drag ? " reordering" : ""}`} ref={activityRef}>
      {/* 固定：资源管理器 + 搜索 */}
      <div className={`icon ${explorerActive ? "active" : ""}`} title={t("activity.explorer", "资源管理器")}
        onClick={() => { setSidebarMode("explorer"); setView("editor"); }}>
        <span className="codicon codicon-files" />
      </div>
      <div className={`icon ${searchActive ? "active" : ""}`} title={t("activity.search", "搜索")}
        onClick={() => setSidebarMode("search")}>
        <span className="codicon codicon-search" />
      </div>

      {/* 可拖动排序的视图工具组 */}
      {order.map((v, idx) => {
        const tool = toolByView.get(v);
        if (!tool) return null;
        return (
          <div
            key={v}
            data-reorder
            className={
              "icon reorderable" +
              (view === v ? " active" : "") +
              (drag && drag.from === idx ? " dragging" : "") +
              (drag && drag.over === idx ? " drag-over" : "")
            }
            title={`${tool.titleKey ? t(tool.titleKey, tool.title) : tool.title}${t("activity.reorderHint", "（按住可上下拖动排序）")}`}
            onMouseDown={(e) => startDrag(e, idx)}
          >
            <span className={`codicon ${tool.icon}`} />
          </div>
        );
      })}

      {/* 固定：终端 + 编辑器折叠 */}
      <div className="icon" title={t("activity.terminal", "终端")} onClick={toggleTerminal}>
        <span className="codicon codicon-terminal" />
      </div>
      <div
        className={`icon ${editorCollapsed ? "active" : ""}`}
        title={editorCollapsed ? t("activity.showEditor", "显示编辑器") : t("activity.hideEditor", "隐藏编辑器")}
        onClick={() => setEditorCollapsed(!editorCollapsed)}
      >
        <span className="codicon codicon-layout" />
      </div>

      <div className="activity-spacer" />
      <div className="icon" title={t("activity.chat", "AI 会话（右侧面板）")} onClick={() => focusChat()}>
        <span className="codicon codicon-sparkle" />
      </div>
      <div className={`icon ${view === "settings" ? "active" : ""}`} title={t("activity.settings", "设置")}
        onClick={() => setView("settings")}>
        <span className="codicon codicon-settings-gear" />
      </div>
    </div>
  );
}
