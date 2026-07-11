import { useEffect, useMemo, useRef, useState } from "react";
import { searchText } from "../../api/searchApi";
import { SearchMatch, SearchResult } from "../../types/search";
import { useWorkbench } from "../../state/workbenchStore";
import { iconForFile } from "../../ui/fileIcons";
import "./search.css";

export function SearchView() {
  const repoId = useWorkbench((s) => s.repoId);
  const openFile = useWorkbench((s) => s.openFile);
  const [query, setQuery] = useState("");
  const [caseSensitive, setCaseSensitive] = useState(false);
  const [result, setResult] = useState<SearchResult | null>(null);
  const [allMatches, setAllMatches] = useState<SearchMatch[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const [offset, setOffset] = useState(0);
  const [loading, setLoading] = useState(false);

  // 代际守卫：每次查询/大小写/仓库变化都会推进代际，防止旧查询的「加载更多」页
  // 追加进新查询的结果列表。
  const searchGenRef = useRef(0);

  useEffect(() => {
    searchGenRef.current += 1;
    const q = query.trim();
    if (q.length < 2 || repoId == null) { setResult(null); setAllMatches([]); setError(null); setOffset(0); return; }
    let ignore = false;
    const timer = setTimeout(async () => {
      try {
        setLoading(true);
        const r = await searchText(repoId, q, caseSensitive, 0, 100);
        if (!ignore) { setResult(r); setAllMatches(r.matches); setError(null); setOffset(0); }
      } catch (e: any) {
        if (!ignore) { setError(e?.message ?? String(e)); setResult(null); setAllMatches([]); setOffset(0); }
      } finally {
        setLoading(false);
      }
    }, 300);
    return () => { ignore = true; clearTimeout(timer); };
  }, [query, caseSensitive, repoId]);

  async function loadMore() {
    const q = query.trim();
    if (q.length < 2 || repoId == null || !result || !result.hasMore || loading) return;
    const myGen = searchGenRef.current;
    try {
      setLoading(true);
      const nextOffset = offset + 100;
      const r = await searchText(repoId, q, caseSensitive, nextOffset, 100);
      // 查询在请求期间已变化则丢弃本页，避免旧查询结果追加进新列表。
      if (searchGenRef.current !== myGen) return;
      if (r) {
        setResult(r);
        setAllMatches((prev) => [...prev, ...r.matches]);
        setOffset(nextOffset);
        setError(null);
      }
    } catch (e: any) {
      if (searchGenRef.current === myGen) setError(e?.message ?? String(e));
    } finally {
      // loading 必须无条件复位，否则查询在途变短(<2字符)时按钮会永久卡在加载态。
      setLoading(false);
    }
  }

  const grouped = useMemo(() => {
    const g = new Map<string, SearchMatch[]>();
    allMatches.forEach((m) => {
      (g.get(m.filePath) ?? g.set(m.filePath, []).get(m.filePath)!).push(m);
    });
    return g;
  }, [allMatches]);

  function toggleFile(f: string) {
    setCollapsed((prev) => {
      const next = new Set(prev);
      next.has(f) ? next.delete(f) : next.add(f);
      return next;
    });
  }

  return (
    <div className="search-view">
      <div className="search-input-row">
        <input autoFocus placeholder="搜索（≥2 字符）" value={query}
          onChange={(e) => setQuery(e.target.value)} />
        <button className={`case-btn ${caseSensitive ? "on" : ""}`} title="区分大小写"
          onClick={() => setCaseSensitive(!caseSensitive)}>Aa</button>
      </div>
      {error && <div className="search-error">{error}</div>}
      {result && (
        <div className="search-summary">
          {result.matchCount} 个结果{result.truncated ? "（已截断，前 500 条）" : ""}
          {result.hasMore && <span> （加载了 {allMatches.length} 条）</span>}
        </div>
      )}
      <div className="search-results">
        {[...grouped.entries()].map(([file, ms]) => {
          const name = file.split("/").pop() ?? file;
          const { icon, color } = iconForFile(name);
          const isCollapsed = collapsed.has(file);
          return (
            <div key={file}>
              <div className="search-file" onClick={() => toggleFile(file)}>
                <span className={`codicon ${isCollapsed ? "codicon-chevron-right" : "codicon-chevron-down"}`} />
                <span className={`codicon ${icon}`} style={{ color }} />
                <span className="search-file-name" title={file}>{name}</span>
                <span className="search-count">{ms.length}</span>
              </div>
              {!isCollapsed && ms.map((m, i) => (
                <div key={`${m.line}-${i}`} className="search-match"
                  onClick={() => openFile(m.filePath, m.line)}>
                  <span className="search-line">{m.line}</span>
                  <span className="search-text">
                    {m.lineContent.slice(0, m.startCol)}
                    <mark>{m.lineContent.slice(m.startCol, m.startCol + result!.query.length)}</mark>
                    {m.lineContent.slice(m.startCol + result!.query.length)}
                  </span>
                </div>
              ))}
            </div>
          );
        })}
        {result && result.hasMore && (
          <div className="search-load-more">
            <button onClick={loadMore} disabled={loading}>
              {loading ? "加载中..." : "加载更多 (还有更多结果)"}
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
