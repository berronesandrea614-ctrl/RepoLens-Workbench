import { useWorkbench } from "../../state/workbenchStore";

export interface Command {
  id: string;
  title: string;
  run: () => void;
}

export function buildCommands(): Command[] {
  const st = () => useWorkbench.getState();
  return [
    { id: "view.explorer", title: "视图: 资源管理器", run: () => { st().setSidebarMode("explorer"); st().setView("editor"); } },
    { id: "view.search", title: "视图: 搜索", run: () => st().setSidebarMode("search") },
    { id: "view.graph", title: "视图: 数据流转图", run: () => st().setView("graph") },
    { id: "view.settings", title: "打开设置", run: () => st().setView("settings") },
    { id: "terminal.toggle", title: "终端: 切换终端面板", run: () => st().toggleTerminal() },
    { id: "chat.new", title: "新建对话", run: () => st().requestNewChat() },
    { id: "repo.importFolder", title: "导入文件夹", run: () => st().requestImportFolder() },
    { id: "repo.importGit", title: "从 Git URL 导入", run: () => st().requestGitImport() },
    { id: "tab.close", title: "文件: 关闭当前标签", run: () => { const p = st().activePath; if (p) st().closeTab(p); } },
  ];
}
