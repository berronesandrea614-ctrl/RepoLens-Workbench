import { useWorkbench } from "../state/workbenchStore";
import { FileTree } from "../features/explorer/FileTree";
import { RepoPicker } from "../features/explorer/RepoPicker";
import { SearchView } from "../features/search/SearchView";
import { useI18n } from "../i18n/I18nProvider";

export function Sidebar() {
  const { t } = useI18n();
  const mode = useWorkbench((s) => s.sidebarMode);
  return (
    <div className="sidebar">
      <div className="sidebar-title">{mode === "explorer" ? t("sidebar.explorer", "资源管理器") : t("sidebar.search", "搜索")}</div>
      {mode === "explorer" ? (
        <>
          <RepoPicker />
          <div className="sidebar-scroll"><FileTree /></div>
        </>
      ) : (
        <SearchView />
      )}
    </div>
  );
}
