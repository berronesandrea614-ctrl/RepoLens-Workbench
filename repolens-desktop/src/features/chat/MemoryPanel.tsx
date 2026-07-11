import { useCallback, useEffect, useState } from "react";
import { fetchMemory, forgetMemory } from "../../api/memoryApi";
import { AgentMemory } from "../../types/memory";
import { useWorkbench } from "../../state/workbenchStore";

function shortDate(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const now = Date.now();
  const diff = now - d.getTime();
  const min = Math.floor(diff / 60000);
  if (min < 1) return "刚刚";
  if (min < 60) return `${min} 分钟前`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return `${hr} 小时前`;
  const day = Math.floor(hr / 24);
  if (day < 7) return `${day} 天前`;
  return d.toLocaleDateString();
}

function splitKeywords(raw: string): string[] {
  return (raw ?? "")
    .split(/[,，;；\s]+/)
    .map((k) => k.trim())
    .filter(Boolean);
}

export function MemoryPanel() {
  const repoId = useWorkbench((s) => s.repoId);
  const [items, setItems] = useState<AgentMemory[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const [forgetting, setForgetting] = useState<number>();

  const load = useCallback(async () => {
    if (!repoId) return;
    setLoading(true);
    setError(undefined);
    try {
      const res = await fetchMemory(repoId);
      setItems(res ?? []);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, [repoId]);

  useEffect(() => {
    void load();
  }, [load]);

  async function forget(id: number) {
    if (!repoId) return;
    setForgetting(id);
    try {
      await forgetMemory(repoId, id);
      await load();
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setForgetting(undefined);
    }
  }

  return (
    <div className="mem">
      <div className="mem-toolbar">
        <span className="mem-count">{items.length} 条记忆</span>
        <button className="chat-tab" onClick={() => void load()} disabled={loading}>
          {loading ? "刷新中…" : "刷新"}
        </button>
      </div>
      {error && <div className="mem-error">{error}</div>}
      {loading && items.length === 0 && <div className="mem-empty">加载中…</div>}
      {!loading && !error && items.length === 0 && (
        <div className="mem-empty">还没有长期记忆——多问几轮，agent 会记住关于这个仓库的事实</div>
      )}
      {items.map((m) => (
        <div key={m.id} className="mem-item">
          <div className="mem-item-head">
            <span className="mem-date">{shortDate(m.createdAt)}</span>
            <button
              className="mem-forget"
              onClick={() => void forget(m.id)}
              disabled={forgetting === m.id}
              title="忘记这条记忆"
            >
              {forgetting === m.id ? "…" : "忘记"}
            </button>
          </div>
          <div className="mem-content">{m.content}</div>
          {splitKeywords(m.keywords).length > 0 && (
            <div className="mem-chips">
              {splitKeywords(m.keywords).map((k, i) => (
                <span key={`${k}:${i}`} className="mem-chip">{k}</span>
              ))}
            </div>
          )}
        </div>
      ))}
    </div>
  );
}
