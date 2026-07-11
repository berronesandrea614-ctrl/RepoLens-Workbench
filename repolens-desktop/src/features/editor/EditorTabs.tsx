import { useWorkbench } from "../../state/workbenchStore";
import { iconForFile } from "../../ui/fileIcons";
import { disposeModelFor } from "./models";

export function EditorTabs({ groupIndex = 0 }: { groupIndex?: number }) {
  const group = useWorkbench((s) => s.groups[groupIndex]);
  const groupCount = useWorkbench((s) => s.groups.length);
  const setActive = useWorkbench((s) => s.setActive);
  const closeTab = useWorkbench((s) => s.closeTab);
  const splitEditor = useWorkbench((s) => s.splitEditor);
  const closeGroup = useWorkbench((s) => s.closeGroup);
  const setActiveGroup = useWorkbench((s) => s.setActiveGroup);

  const tabs = group?.tabs ?? [];
  const activePath = group?.activePath ?? null;

  // 单组且无标签时保持与旧版一致（不渲染标签栏）；分屏后始终渲染以保留关闭按钮。
  if (groupCount === 1 && tabs.length === 0) return null;

  function close(path: string, dirty: boolean) {
    if (dirty && !window.confirm("未保存的更改将丢失，确认关闭？")) return;
    closeTab(path, groupIndex);
    // 仅当其它分组不再打开该文件时才释放 model，避免影响并排分组。
    const stillOpen = useWorkbench
      .getState()
      .groups.some((g) => g.tabs.some((t) => t.path === path));
    const rid = useWorkbench.getState().repoId;
    if (!stillOpen && rid != null) disposeModelFor(rid, path);
  }

  return (
    <div className="tabs" onMouseDown={() => setActiveGroup(groupIndex)}>
      {tabs.map((t) => {
        const name = t.path.split("/").pop() ?? t.path;
        const { icon, color } = iconForFile(name);
        return (
          <div
            key={t.path}
            className={`tab ${t.path === activePath ? "active" : ""} ${t.dirty ? "dirty" : ""}`}
            title={t.path}
            onClick={() => setActive(t.path, groupIndex)}
            onAuxClick={(e) => {
              if (e.button === 1) close(t.path, t.dirty);
            }}
          >
            <span className={`codicon ${icon}`} style={{ color }} />
            <span className="tab-name">{name}</span>
            <span
              className="tab-dirty codicon codicon-circle-filled"
              onClick={(e) => {
                e.stopPropagation();
                close(t.path, t.dirty);
              }}
            />
            <span
              className="tab-close codicon codicon-close"
              onClick={(e) => {
                e.stopPropagation();
                close(t.path, t.dirty);
              }}
            />
          </div>
        );
      })}
      <div className="tabs-actions">
        {groupCount < 2 && (
          <span
            className="tabs-action codicon codicon-split-horizontal"
            title="拆分编辑器"
            onClick={(e) => {
              e.stopPropagation();
              splitEditor();
            }}
          />
        )}
        {groupCount > 1 && (
          <span
            className="tabs-action codicon codicon-close"
            title="关闭分组"
            onClick={(e) => {
              e.stopPropagation();
              closeGroup(groupIndex);
            }}
          />
        )}
      </div>
    </div>
  );
}
