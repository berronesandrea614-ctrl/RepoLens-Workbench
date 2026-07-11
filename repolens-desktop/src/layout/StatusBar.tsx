import { useEffect, useState } from "react";
import { useWorkbench } from "../state/workbenchStore";
import { useI18n } from "../i18n/I18nProvider";
import { http } from "../api/http";
import { languageFromPath } from "../api/fileApi";
import { parseRepo, buildChunks, buildVectors } from "../api/repoApi";
import {
  getPrivacyStatus,
  getPrivacyBadgeLabel,
  getPrivacyBadgeClass,
  type PrivacyMode,
} from "../api/privacyApi";

export function StatusBar() {
  const { t } = useI18n();
  const repoId = useWorkbench((s) => s.repoId);
  const cursor = useWorkbench((s) => s.cursor);
  const activePath = useWorkbench((s) => s.activePath);
  const indexStale = useWorkbench((s) => s.indexStale);
  const clearIndexStale = useWorkbench((s) => s.clearIndexStale);
  const refreshTree = useWorkbench((s) => s.refreshTree);
  const [name, setName] = useState("");
  const [reindexing, setReindexing] = useState(false);
  const [toast, setToast] = useState<string | null>(null);
  const [privacyMode, setPrivacyMode] = useState<PrivacyMode | null>(null);

  // Fetch repo name
  useEffect(() => {
    if (repoId == null) {
      setName("");
      return;
    }
    let ignore = false;
    http
      .get(`/api/repos/${repoId}`)
      .then((r: any) => {
        if (!ignore) setName(r?.repoName ?? `repo#${repoId}`);
      })
      .catch(() => {
        if (!ignore) setName(`repo#${repoId}`);
      });
    return () => {
      ignore = true;
    };
  }, [repoId]);

  // Fetch privacy mode (poll every 30s for real-time badge update)
  useEffect(() => {
    let ignore = false;
    function fetchMode() {
      getPrivacyStatus()
        .then((s) => {
          if (!ignore) setPrivacyMode(s.mode);
        })
        .catch(() => {
          // fail silently — badge shows default
        });
    }
    fetchMode();
    const timer = setInterval(fetchMode, 30_000);
    return () => {
      ignore = true;
      clearInterval(timer);
    };
  }, []);

  async function reindex() {
    if (repoId == null || reindexing) return;
    setReindexing(true);
    setToast("正在重建索引…");
    try {
      await parseRepo(repoId);
      await buildChunks(repoId);
      await buildVectors(repoId);
      clearIndexStale();
      refreshTree();
      setToast("索引已更新");
      setTimeout(() => setToast(null), 2500);
    } catch (e) {
      setToast(`重建索引失败：${e instanceof Error ? e.message : String(e)}`);
      setTimeout(() => setToast(null), 4000);
    } finally {
      setReindexing(false);
    }
  }

  const repoLabel = repoId == null ? t("status.noRepo", "未打开仓库") : name || `repo#${repoId}`;

  const badgeMode = privacyMode ?? "OPEN";
  const badgeLabel = getPrivacyBadgeLabel(badgeMode);
  const badgeClass = getPrivacyBadgeClass(badgeMode);

  return (
    <div className="statusbar">
      <span className={`privacy-badge ${badgeClass}`}>{badgeLabel}</span>
      <span><span className="codicon codicon-repo" /> {repoLabel}</span>
      {repoId != null && indexStale && (
        <button
          className="stale"
          title="索引可能已过期，点击重建（解析→切片→向量）"
          onClick={() => void reindex()}
          disabled={reindexing}
        >
          <span className={`codicon ${reindexing ? "codicon-sync codicon-modifier-spin" : "codicon-warning"}`} />{" "}
          {reindexing ? t("status.reindexing", "重建索引中…") : t("status.staleIndex", "索引已过期 · 点击重建")}
        </button>
      )}
      {toast && <span className="statusbar-toast">{toast}</span>}
      <span className="right">
        {cursor && activePath && <span>行 {cursor.line}, 列 {cursor.col}</span>}
        <span>空格: 2</span>
        <span>UTF-8</span>
        {activePath && <span>{languageFromPath(activePath)}</span>}
      </span>
    </div>
  );
}
