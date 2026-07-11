import { useWorkbench } from "../../state/workbenchStore";
import "./welcome.css";

const IS_MAC =
  typeof navigator !== "undefined" && /Mac|iPhone|iPad/.test(navigator.platform || "");
const MOD = IS_MAC ? "⌘" : "Ctrl";

/** 冷启动 / 未打开仓库时的欢迎页：引导导入第一个仓库。 */
export function WelcomeView() {
  const requestImportFolder = useWorkbench((s) => s.requestImportFolder);
  const requestGitImport = useWorkbench((s) => s.requestGitImport);

  return (
    <div className="welcome">
      <div className="welcome-card">
        <div className="welcome-icon">
          <span className="codicon codicon-repo" />
        </div>
        <h1 className="welcome-title">欢迎使用 RepoLens</h1>
        <p className="welcome-sub">导入你的第一个仓库开始</p>

        <div className="welcome-actions">
          <button className="welcome-btn primary" onClick={requestImportFolder}>
            <span className="codicon codicon-folder-opened" />
            打开文件夹
          </button>
          <button className="welcome-btn" onClick={requestGitImport}>
            <span className="codicon codicon-git-merge" />
            从 Git URL 导入
          </button>
        </div>

        <ul className="welcome-hints">
          <li>
            <kbd>{MOD}P</kbd> 快速打开文件 / 命令
          </li>
          <li>
            <span className="codicon codicon-book" /> 导入后可用 AI 会话、数据流转图与需求流
          </li>
          <li>代码全程本地处理，不出网</li>
        </ul>
      </div>
    </div>
  );
}
