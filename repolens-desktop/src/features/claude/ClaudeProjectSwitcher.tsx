import { useEffect, useState } from "react";
import { listRepos, type RepoVO } from "../../api/repoApi";
import { useClaudeStore } from "../../state/claudeStore";
import { dedupeByRealDir, repoRealDir } from "./realDir";
import { FeishuBindingPanel } from "./FeishuBindingPanel";
import { avatarColor, initial, filterProjects } from "./projectSwitcherHelpers";

const IS_TAURI = "__TAURI_INTERNALS__" in window;

interface ClaudeMdState {
  repoId: number;
  realDir: string;
  content: string;
}

interface Props {
  /** Called when the user clicks a local repo row. */
  onActivate: (repoId: number, realDir: string) => void;
}

/**
 * ClaudeProjectSwitcher — redesigned project list (§3c.3).
 *
 * Improvements over previous version:
 *   - Coloured letter avatar per project (stable hash → HSL).
 *   - Taller rows: name + dir/status on two lines, no over-truncation.
 *   - Status badges (🟢 活跃 / ⚪ 休眠 / 🔄 运行中) instead of bare dots.
 *   - 🔗 indicator badge when at least one Feishu binding exists for the repo.
 *   - Hover action bar (📝 CLAUDE.md / 🔗 Feishu / ✕ close) instead of
 *     inline buttons — cleaner when not hovered.
 *   - Search / filter box at the top.
 *   - Friendly empty-state guide when no repos have been imported.
 *   - Remote/snapshot repos labelled "不可用 Claude Code".
 *
 * Task-4 Feishu integration (FeishuBindingPanel) is fully preserved.
 */
export function ClaudeProjectSwitcher({ onActivate }: Props) {
  const [repos, setRepos] = useState<RepoVO[]>([]);
  const [loading, setLoading] = useState(true);
  const [claudeMd, setClaudeMd] = useState<ClaudeMdState | null>(null);
  const [feishuRepoId, setFeishuRepoId] = useState<number | null>(null);
  const [query, setQuery] = useState("");

  const sessions = useClaudeStore((s) => s.sessions);
  const activeRepoId = useClaudeStore((s) => s.activeRepoId);
  const markDormant = useClaudeStore((s) => s.markDormant);
  const feishuBindings = useClaudeStore((s) => s.feishuBindings);

  // Fetch repo list once on mount.
  useEffect(() => {
    listRepos()
      .then((list) => {
        setRepos(list);
        setLoading(false);
      })
      .catch(() => setLoading(false));
  }, []);

  async function handleViewClaudeMd(repoId: number, realDir: string) {
    if (!IS_TAURI) return;
    const filePath = realDir.replace(/\/$/, "") + "/CLAUDE.md";
    try {
      const { invoke } = await import("@tauri-apps/api/core");
      const content = (await invoke("read_text_file", {
        path: filePath,
        baseDir: realDir,
      })) as string;
      setClaudeMd({ repoId, realDir, content });
    } catch {
      setClaudeMd({
        repoId,
        realDir,
        content: "（CLAUDE.md 不存在或无法读取）",
      });
    }
  }

  function getStatusBadge(repoId: number): { label: string; cls: string } {
    const s = sessions[repoId];
    if (!s || s.ptyId == null) return { label: "⚪ 休眠", cls: "dormant" };
    if (s.status === "running") return { label: "🔄 运行中", cls: "running" };
    return { label: "🟢 活跃", cls: "live" };
  }

  if (loading) {
    return (
      <div className="claude-switcher">
        <div className="claude-switcher-loading">加载项目列表…</div>
      </div>
    );
  }

  // C1: Deduplicate by realDir so the same physical directory only shows once.
  const localRepos = dedupeByRealDir(repos.filter((r) => repoRealDir(r) != null));
  const remoteRepos = repos.filter((r) => repoRealDir(r) == null);

  const filteredLocal = filterProjects(localRepos, query);
  const filteredRemote = filterProjects(remoteRepos, query);

  const hasAnyRepos = localRepos.length > 0 || remoteRepos.length > 0;
  const hasNoMatches =
    query.trim() !== "" &&
    filteredLocal.length === 0 &&
    filteredRemote.length === 0;

  return (
    <div className="claude-switcher">
      <div className="claude-switcher-header">项目列表</div>

      {/* Search / filter */}
      <div className="claude-switcher-search">
        <input
          className="claude-switcher-search-input"
          type="text"
          placeholder="过滤项目…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          aria-label="过滤项目"
          spellCheck={false}
        />
      </div>

      {/* Empty state — no repos imported yet */}
      {!hasAnyRepos && (
        <div className="claude-switcher-empty">
          <div className="claude-switcher-empty-icon">📂</div>
          <div>暂无本地仓库</div>
          <div className="claude-switcher-empty-hint">
            点击「打开文件夹」导入你的第一个仓库
          </div>
        </div>
      )}

      {/* No-match state — repos exist but filter returns nothing */}
      {hasAnyRepos && hasNoMatches && (
        <div className="claude-switcher-empty">无匹配项目</div>
      )}

      {/* ── Local repo rows ── */}
      {filteredLocal.map((repo) => {
        const realDir = repoRealDir(repo)!;
        const isActive = repo.id === activeRepoId;
        const badge = getStatusBadge(repo.id);
        const hasFeishu = (feishuBindings[repo.id]?.length ?? 0) > 0;
        const isLive = sessions[repo.id]?.ptyId != null;

        // Show the last two path segments for a compact label.
        const dirParts = realDir.split("/");
        const dirLabel = dirParts.slice(-2).join("/");

        // Full tooltip: name + real path for same-name disambiguation.
        const tooltip = `${repo.repoName}\n${realDir}`;

        return (
          <div
            key={repo.id}
            className={`claude-project-row${isActive ? " claude-project-row--active" : ""}`}
          >
            {/* Coloured letter avatar */}
            <div
              className="claude-project-avatar"
              style={{ backgroundColor: avatarColor(repo.repoName) }}
              aria-hidden="true"
            >
              {initial(repo.repoName)}
            </div>

            {/* Clickable info area — activates the project */}
            <button
              className="claude-project-btn"
              onClick={() => onActivate(repo.id, realDir)}
              title={tooltip}
            >
              <div className="claude-project-main">
                <span className="claude-project-name">{repo.repoName}</span>
                {hasFeishu && (
                  <span className="claude-project-feishu-dot" title="已绑定飞书">
                    🔗
                  </span>
                )}
              </div>
              <div className="claude-project-sub">
                <span className="claude-project-dir" title={realDir}>
                  {dirLabel}
                </span>
                <span
                  className={`claude-project-badge claude-project-badge--${badge.cls}`}
                >
                  {badge.label}
                </span>
              </div>
            </button>

            {/* Hover action bar — replaces inline 📝/🔗 buttons */}
            <div className="claude-project-actions" role="group" aria-label="项目操作">
              {IS_TAURI && (
                <button
                  className="claude-project-action-btn"
                  onClick={(e) => {
                    e.stopPropagation();
                    handleViewClaudeMd(repo.id, realDir);
                  }}
                  title="查看 CLAUDE.md"
                  aria-label="查看 CLAUDE.md"
                >
                  📝
                </button>
              )}
              {/* Task-4: Feishu binding entry point — always present */}
              <button
                className="claude-project-action-btn"
                onClick={(e) => {
                  e.stopPropagation();
                  setFeishuRepoId(repo.id);
                }}
                title="接入飞书远程控制"
                aria-label="接入飞书远程控制"
              >
                🔗
              </button>
              {isLive && (
                <button
                  className="claude-project-action-btn claude-project-action-btn--danger"
                  onClick={(e) => {
                    e.stopPropagation();
                    markDormant(repo.id);
                  }}
                  title="关闭会话"
                  aria-label="关闭会话"
                >
                  ✕
                </button>
              )}
            </div>
          </div>
        );
      })}

      {/* ── Remote / snapshot repos ── */}
      {filteredRemote.length > 0 && (
        <>
          <div className="claude-switcher-section">只读快照（不可用 Claude Code）</div>
          {filteredRemote.map((repo) => (
            <div
              key={repo.id}
              className="claude-project-row claude-project-row--disabled"
              title="此仓库为只读快照，无法用 Claude Code 开发"
            >
              <div
                className="claude-project-avatar claude-project-avatar--dim"
                aria-hidden="true"
              >
                {initial(repo.repoName)}
              </div>
              <div className="claude-project-info">
                <div className="claude-project-main">
                  <span className="claude-project-name">{repo.repoName}</span>
                </div>
              </div>
              <span className="claude-project-tag">只读快照</span>
            </div>
          ))}
        </>
      )}

      {/* Feishu binding panel modal (Task 4 — preserved) */}
      {feishuRepoId != null && (
        <FeishuBindingPanel
          repoId={feishuRepoId}
          onClose={() => setFeishuRepoId(null)}
        />
      )}

      {/* CLAUDE.md viewer modal */}
      {claudeMd && (
        <div
          className="claude-md-overlay"
          role="dialog"
          aria-modal="true"
          aria-label="CLAUDE.md 查看"
          onClick={() => setClaudeMd(null)}
        >
          <div
            className="claude-md-modal"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="claude-md-modal-header">
              <span>📄 CLAUDE.md</span>
              <span className="claude-md-path" title={claudeMd.realDir}>
                {claudeMd.realDir.split("/").slice(-2).join("/")}
              </span>
              <button
                className="claude-md-close"
                onClick={() => setClaudeMd(null)}
                aria-label="关闭"
              >
                ✕
              </button>
            </div>
            <pre className="claude-md-content">{claudeMd.content}</pre>
          </div>
        </div>
      )}
    </div>
  );
}
