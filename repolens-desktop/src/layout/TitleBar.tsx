import { useWorkbench } from "../state/workbenchStore";
import { useI18n } from "../i18n/I18nProvider";

const IS_TAURI = "__TAURI_INTERNALS__" in window;

export function TitleBar() {
  const { t } = useI18n();
  const repoId = useWorkbench((s) => s.repoId);
  const editorCollapsed = useWorkbench((s) => s.editorCollapsed);
  const setEditorCollapsed = useWorkbench((s) => s.setEditorCollapsed);
  return (
    <div className="title-bar" data-tauri-drag-region>
      {IS_TAURI && <div className="traffic-light-spacer" />}
      <span className="title-text">
        RepoLens{repoId == null ? ` — ${t("titlebar.noRepo", "未打开仓库")}` : ` — repo #${repoId}`}
      </span>
      <button
        className="title-editor-toggle"
        title={editorCollapsed ? t("titlebar.showEditorTip", "显示中间编辑器区") : t("titlebar.hideEditorTip", "隐藏中间编辑器区（点文件或左侧视图会自动打开）")}
        onClick={() => setEditorCollapsed(!editorCollapsed)}
      >
        <span
          className={`codicon ${editorCollapsed ? "codicon-layout-sidebar-right" : "codicon-chrome-close"}`}
        />
        {editorCollapsed ? t("titlebar.showEditor", "显示编辑器") : t("titlebar.hideEditor", "隐藏编辑器")}
      </button>
    </div>
  );
}
