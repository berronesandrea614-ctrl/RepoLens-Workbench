import { useEffect, useRef, useState } from "react";
import { useWorkbench } from "../../state/workbenchStore";
import {
  listRepos,
  createRepo,
  importRepo,
  parseRepo,
  buildChunks,
  buildVectors,
  backgroundIndex,
  deleteRepo,
  fileUrlForPath,
  type RepoVO,
} from "../../api/repoApi";
import { reconcileRepoId } from "./repoReconcile";
import { useI18n } from "../../i18n/I18nProvider";
import "./repoPicker.css";

const IS_TAURI = "__TAURI_INTERNALS__" in window;

type StepStatus = "pending" | "running" | "done" | "error";
type StepKey = "create" | "import" | "parse" | "chunks" | "vectors";
interface Step {
  key: StepKey;
  label: string;
  status: StepStatus;
}

const STEP_DEFS: Record<Exclude<StepKey, "create">, { label: string; run: (id: number) => Promise<unknown> }> = {
  import: { label: "导入 (import)", run: importRepo },
  parse: { label: "解析符号 (parse)", run: parseRepo },
  chunks: { label: "构建切片 (chunks)", run: buildChunks },
  vectors: { label: "构建向量 (vectors)", run: buildVectors },
};

// 惰性后台索引：导入不再阻塞跑 import→parse→chunk→vector。create 后 fire 一次
// backgroundIndex（后端后台线程跑整条流水线），向导立即完成、仓库即刻可用。
// 故这两个「阻塞步骤列表」现在为空；索引在后台进行，符号视图按索引状态展示。
const FULL_STEPS: Exclude<StepKey, "create">[] = [];
const REINDEX_STEPS: Exclude<StepKey, "create">[] = [];

function basename(p: string): string {
  const parts = p.replace(/[\\/]+$/, "").split(/[\\/]/);
  return parts[parts.length - 1] || p;
}

/** 从 git url 推导仓库名：末段去掉 .git。 */
function repoNameFromGitUrl(url: string): string {
  const trimmed = url.trim().replace(/\/+$/, "").replace(/\.git$/i, "");
  const seg = trimmed.split(/[\\/:]/).filter(Boolean).pop() ?? "";
  return seg || "repo";
}

function errMsg(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

type Mode = "folder" | "git";

interface RunConfig {
  // create 阶段所需（存在则先创建仓库）
  create?: { repoUrl: string; name: string; branchName: string };
  // 复用已有仓库 id（去重重新索引 / 失败重试）
  repoId?: number;
  stepKeys: Exclude<StepKey, "create">[];
}

export function RepoPicker() {
  const { t } = useI18n();
  const repoId = useWorkbench((s) => s.repoId);
  const setRepoId = useWorkbench((s) => s.setRepoId);
  const refreshTree = useWorkbench((s) => s.refreshTree);
  const importFolderNonce = useWorkbench((s) => s.importFolderNonce);
  const gitImportNonce = useWorkbench((s) => s.gitImportNonce);

  const [repos, setRepos] = useState<RepoVO[]>([]);
  const [loadError, setLoadError] = useState<string | null>(null);

  // import flow state
  const [picking, setPicking] = useState(false);
  const [mode, setMode] = useState<Mode>("folder");
  const [pathInput, setPathInput] = useState("");
  const [nameInput, setNameInput] = useState("");
  const [gitUrl, setGitUrl] = useState("");
  const [gitBranch, setGitBranch] = useState("main");
  const [running, setRunning] = useState(false);
  const [steps, setSteps] = useState<Step[]>([]);
  const [failedIndex, setFailedIndex] = useState<number | null>(null);
  const [stepError, setStepError] = useState<string | null>(null);
  // 失败后重试所需的完整配置（create 若已成功则改用 repoId 续跑）。
  const retryCfgRef = useRef<RunConfig | null>(null);
  // 本地文件夹去重提示。
  const [dup, setDup] = useState<{ repo: RepoVO } | null>(null);

  async function refreshRepos(): Promise<RepoVO[]> {
    try {
      const list = await listRepos();
      setRepos(list);
      setLoadError(null);
      // Reconcile stale persisted repoId (e.g., left over from a previous user
      // or a repo that was deleted). Read the id from the store at call-time to
      // avoid stale-closure issues when refreshRepos is invoked after state changes.
      const currentId = useWorkbench.getState().repoId;
      if (reconcileRepoId(currentId, list) !== currentId) {
        setRepoId(null);
      }
      return list;
    } catch (e) {
      setLoadError(errMsg(e));
      return [];
    }
  }

  useEffect(() => {
    void refreshRepos();
  }, []);

  // 欢迎页 / 命令面板触发：打开本地文件夹选择。
  useEffect(() => {
    if (importFolderNonce > 0) void chooseFolder();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [importFolderNonce]);

  // 欢迎页触发：打开 Git URL 导入表单。
  useEffect(() => {
    if (gitImportNonce > 0) chooseGit();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [gitImportNonce]);

  function resetFlow() {
    setSteps([]);
    setFailedIndex(null);
    setStepError(null);
    setDup(null);
    retryCfgRef.current = null;
  }

  async function chooseFolder() {
    setMode("folder");
    if (IS_TAURI) {
      try {
        const { open } = await import("@tauri-apps/plugin-dialog");
        const picked = await open({ directory: true, multiple: false, title: "选择本地代码文件夹" });
        if (typeof picked === "string" && picked) {
          setPathInput(picked);
          setNameInput(basename(picked));
          resetFlow();
          setPicking(true);
        }
      } catch (e) {
        setLoadError(errMsg(e));
      }
    } else {
      setPathInput("");
      setNameInput("");
      resetFlow();
      setPicking(true);
    }
  }

  function chooseGit() {
    setMode("git");
    setGitUrl("");
    setGitBranch("main");
    setNameInput("");
    resetFlow();
    setPicking(true);
  }

  // 执行 create（可选）+ 指定步骤，全部按 repoId 串行推进。
  async function runPipeline(cfg: RunConfig) {
    setRunning(true);
    setFailedIndex(null);
    setStepError(null);
    setDup(null);

    const displaySteps: Step[] = [];
    if (cfg.create) displaySteps.push({ key: "create", label: "创建仓库 (create)", status: "pending" });
    for (const k of cfg.stepKeys) displaySteps.push({ key: k, label: STEP_DEFS[k].label, status: "pending" });
    setSteps(displaySteps);

    const setStatus = (idx: number, status: StepStatus) =>
      setSteps((prev) => prev.map((s, i) => (i === idx ? { ...s, status } : s)));

    let rid = cfg.repoId;
    let cursor = 0;

    if (cfg.create) {
      setStatus(0, "running");
      try {
        const created = await createRepo({
          workspaceId: 1,
          repoName: cfg.create.name,
          repoUrl: cfg.create.repoUrl,
          branchName: cfg.create.branchName,
        });
        rid = created.id;
        setStatus(0, "done");
      } catch (e) {
        setStatus(0, "error");
        setFailedIndex(0);
        setStepError(errMsg(e));
        // create 失败：重试仍需从 create 开始。
        retryCfgRef.current = cfg;
        setRunning(false);
        return;
      }
      cursor = 1;
    }

    // 惰性后台索引：仓库已创建/就绪即可用（浏览文件走真实目录、飞书可跑），
    // 索引 import→parse→chunk→vector 在后端后台线程进行，不阻塞向导。
    if (rid != null) {
      void backgroundIndex(rid).catch(() => {
        /* 后台索引触发失败不影响导入完成；符号视图会提示「尚未索引」，可手动重算 */
      });
    }

    for (let i = 0; i < cfg.stepKeys.length; i++) {
      const idx = cursor + i;
      setStatus(idx, "running");
      try {
        await STEP_DEFS[cfg.stepKeys[i]].run(rid!);
        setStatus(idx, "done");
      } catch (e) {
        setStatus(idx, "error");
        setFailedIndex(idx);
        setStepError(errMsg(e));
        // create 已成功：续跑时改用 repoId 且不再重复已完成的步骤。
        retryCfgRef.current = { repoId: rid, stepKeys: cfg.stepKeys.slice(i) };
        setRunning(false);
        return;
      }
    }

    // 全部成功
    setRunning(false);
    setPicking(false);
    resetFlow();
    setRepoId(rid!);
    refreshTree();
    await refreshRepos();
  }

  async function startFlow() {
    if (mode === "git") {
      const url = gitUrl.trim();
      if (!url) {
        setStepError("请填写 Git 仓库地址");
        return;
      }
      const name = nameInput.trim() || repoNameFromGitUrl(url);
      const branch = gitBranch.trim() || "main";
      await runPipeline({ create: { repoUrl: url, name, branchName: branch }, stepKeys: FULL_STEPS });
      return;
    }

    // folder mode
    const abs = pathInput.trim();
    if (!abs) {
      setStepError("请填写文件夹绝对路径");
      return;
    }
    const name = nameInput.trim() || basename(abs);
    const url = fileUrlForPath(abs);

    // 去重：同一 file:// 路径已导入则提示。
    const list = await refreshRepos();
    const existing = list.find((r) => r.repoUrl && r.repoUrl === url);
    if (existing) {
      setDup({ repo: existing });
      return;
    }
    await runPipeline({ create: { repoUrl: url, name, branchName: "main" }, stepKeys: FULL_STEPS });
  }

  function retryFlow() {
    const cfg = retryCfgRef.current;
    if (cfg) void runPipeline(cfg);
  }

  // 去重提示的三个选项
  function dupOpenExisting() {
    if (!dup) return;
    const id = dup.repo.id;
    setPicking(false);
    resetFlow();
    setRepoId(id);
  }
  function dupReindex() {
    if (!dup) return;
    void runPipeline({ repoId: dup.repo.id, stepKeys: REINDEX_STEPS });
  }

  function cancelFlow() {
    setPicking(false);
    resetFlow();
  }

  const stepIcon = (s: StepStatus) => {
    switch (s) {
      case "done":
        return "✓";
      case "error":
        return "✗";
      case "running":
        return "…";
      default:
        return "○";
    }
  };

  const hasCurrentRepo = repos.some((r) => r.id === repoId);

  // 删除当前选中仓库：二次确认 → 调用后端 → 刷新列表 → 若删的是当前选中则切到剩余首个（无则清空）。
  async function handleDeleteRepo() {
    if (repoId == null || running) return;
    const target = repos.find((r) => r.id === repoId);
    const name = target?.repoName ?? `#${repoId}`;
    if (!window.confirm(`确定删除仓库「${name}」及其全部索引数据？此操作不可撤销。`)) return;
    try {
      await deleteRepo(repoId);
      const list = await refreshRepos();
      // refreshRepos 内部的 reconcile 已可能把 repoId 置空；这里显式切到剩余首个仓库。
      const remaining = list.filter((r) => r.id !== repoId);
      setRepoId(remaining.length > 0 ? remaining[0].id : null);
    } catch (e) {
      setLoadError(errMsg(e));
    }
  }

  return (
    <div className="repo-picker">
      <div className="repo-picker-row">
        <span className="repo-picker-label">{t("repo.label", "仓库")}</span>
        <select
          className="repo-picker-select"
          value={hasCurrentRepo ? String(repoId) : ""}
          onChange={(e) => e.target.value && setRepoId(Number(e.target.value))}
        >
          {!hasCurrentRepo && <option value="">{repoId ? `#${repoId}` : t("repo.select", "选择仓库")}</option>}
          {repos.map((r) => (
            <option key={r.id} value={r.id}>
              {r.repoName}
              {r.indexStatus ? ` · ${r.indexStatus}` : ""}
            </option>
          ))}
        </select>
        {hasCurrentRepo && (
          <button
            className="repo-picker-btn danger"
            onClick={() => void handleDeleteRepo()}
            disabled={running}
            title={t("repo.deleteCurrent", "删除当前仓库")}
          >
            🗑
          </button>
        )}
        <button className="repo-picker-btn" onClick={() => void chooseFolder()} disabled={running} title={t("repo.importFolderTip", "导入本地文件夹")}>
          {t("repo.openFolder", "打开文件夹…")}
        </button>
        <button className="repo-picker-btn" onClick={chooseGit} disabled={running} title={t("repo.importGitTip", "从 Git URL 导入")}>
          {t("repo.gitUrl", "Git URL")}
        </button>
      </div>

      {loadError && <div className="repo-picker-err">{loadError}</div>}

      {picking && (
        <div className="repo-picker-panel">
          {mode === "folder" ? (
            <>
              {!IS_TAURI && (
                <label className="repo-picker-field">
                  <span>{t("repo.folderAbsPath", "文件夹绝对路径")}</span>
                  <input
                    value={pathInput}
                    onChange={(e) => setPathInput(e.target.value)}
                    placeholder="/Users/you/code/myrepo"
                    disabled={running}
                  />
                </label>
              )}
              {IS_TAURI && (
                <div className="repo-picker-path" title={pathInput}>
                  {pathInput}
                </div>
              )}
            </>
          ) : (
            <>
              <label className="repo-picker-field">
                <span>{t("repo.gitAddress", "Git 仓库地址")}</span>
                <input
                  value={gitUrl}
                  onChange={(e) => {
                    setGitUrl(e.target.value);
                    if (!nameInput.trim()) setNameInput("");
                  }}
                  placeholder="https://github.com/owner/repo.git 或 git@…"
                  disabled={running}
                />
              </label>
              <label className="repo-picker-field">
                <span>{t("repo.branch", "分支")}</span>
                <input
                  value={gitBranch}
                  onChange={(e) => setGitBranch(e.target.value)}
                  placeholder="main"
                  disabled={running}
                />
              </label>
            </>
          )}
          <label className="repo-picker-field">
            <span>{t("repo.name", "仓库名称")}</span>
            <input
              value={nameInput}
              onChange={(e) => setNameInput(e.target.value)}
              placeholder={mode === "git" ? repoNameFromGitUrl(gitUrl) || "repo" : "myrepo"}
              disabled={running}
            />
          </label>

          {dup && (
            <div className="repo-picker-dup">
              <div className="repo-picker-dup-msg">
                该文件夹已导入为「{dup.repo.repoName}」，是否重新索引？
              </div>
              <div className="repo-picker-actions">
                <button className="repo-picker-btn" onClick={dupOpenExisting} disabled={running}>
                  {t("repo.openExisting", "打开已有")}
                </button>
                <button className="repo-picker-btn primary" onClick={dupReindex} disabled={running}>
                  {t("repo.reindex", "重新索引")}
                </button>
                <button className="repo-picker-btn" onClick={cancelFlow} disabled={running}>
                  {t("common.cancel", "取消")}
                </button>
              </div>
            </div>
          )}

          {steps.length > 0 && (
            <ul className="repo-picker-steps">
              {steps.map((s) => (
                <li key={s.key} className={`step step-${s.status}`}>
                  <span className="step-icon">{stepIcon(s.status)}</span>
                  <span className="step-label">{s.label}</span>
                </li>
              ))}
            </ul>
          )}

          {failedIndex != null && (
            <div className="repo-picker-err">
              步骤「{steps[failedIndex]?.label}」失败：{stepError ?? "请查看后端日志"}
            </div>
          )}
          {failedIndex == null && stepError && <div className="repo-picker-err">{stepError}</div>}

          {!dup && (
            <div className="repo-picker-actions">
              {failedIndex != null ? (
                <button className="repo-picker-btn primary" onClick={retryFlow} disabled={running}>
                  {t("common.retry", "重试")}
                </button>
              ) : (
                <button
                  className="repo-picker-btn primary"
                  onClick={() => void startFlow()}
                  disabled={running || steps.length > 0}
                >
                  {running ? t("repo.importing", "导入中…") : t("repo.startImport", "开始导入")}
                </button>
              )}
              <button className="repo-picker-btn" onClick={cancelFlow} disabled={running}>
                {t("common.cancel", "取消")}
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
