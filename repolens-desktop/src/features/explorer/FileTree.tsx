import { useEffect, useState } from "react";
import { fetchTree } from "../../api/treeApi";
import { FileTreeNode } from "../../types/tree";
import { useWorkbench } from "../../state/workbenchStore";
import { iconForFile } from "../../ui/fileIcons";

export function flattenTree(node: FileTreeNode): string[] {
  if (!node.directory) return node.path ? [node.path] : [];
  return (node.children ?? []).flatMap(flattenTree);
}

function TreeNode({ node, depth }: { node: FileTreeNode; depth: number }) {
  const [open, setOpen] = useState(depth < 1);
  const openFile = useWorkbench((s) => s.openFile);
  const activePath = useWorkbench((s) => s.activePath);
  const pad = 8 + depth * 12;

  if (!node.directory) {
    const { icon, color } = iconForFile(node.name);
    return (
      <div className={`tree-row ${activePath === node.path ? "active" : ""}`}
        style={{ paddingLeft: pad + 16 }}
        onClick={() => openFile(node.path)}>
        <span className={`codicon ${icon}`} style={{ color }} />
        <span className="tree-label">{node.name}</span>
      </div>
    );
  }
  return (
    <div>
      {node.name && (
        <div className="tree-row" style={{ paddingLeft: pad }} onClick={() => setOpen(!open)}>
          <span className={`codicon ${open ? "codicon-chevron-down" : "codicon-chevron-right"}`} />
          <span className={`codicon ${open ? "codicon-folder-opened" : "codicon-folder"}`}
            style={{ color: "#dcb67a" }} />
          <span className="tree-label">{node.name}</span>
        </div>
      )}
      {(open || !node.name) && node.children?.map((c) => (
        <TreeNode key={c.path} node={c} depth={node.name ? depth + 1 : depth} />
      ))}
    </div>
  );
}

export function FileTree() {
  const repoId = useWorkbench((s) => s.repoId);
  const treeRefreshNonce = useWorkbench((s) => s.treeRefreshNonce);
  const refreshTree = useWorkbench((s) => s.refreshTree);
  const [tree, setTree] = useState<FileTreeNode | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (repoId == null) {
      setTree(null);
      setError(null);
      return;
    }
    setError(null);
    setLoading(true);
    let ignore = false;
    fetchTree(repoId)
      .then((t) => {
        if (!ignore) setTree(t);
      })
      .catch((e) => {
        if (!ignore) setError(String(e.message ?? e));
      })
      .finally(() => {
        if (!ignore) setLoading(false);
      });
    return () => {
      ignore = true;
    };
  }, [repoId, treeRefreshNonce]);

  if (repoId == null) {
    return <div style={{ padding: 12, color: "var(--vs-fg-dim)" }}>未打开仓库</div>;
  }

  return (
    <>
      <div className="tree-header">
        <span className="tree-header-title">文件</span>
        <button
          className="tree-header-btn codicon codicon-refresh"
          title="刷新文件树"
          onClick={() => refreshTree()}
          disabled={loading}
        />
      </div>
      {error ? (
        <div style={{ padding: 12, color: "#f48771" }}>{error}</div>
      ) : !tree ? (
        <div style={{ padding: 12, color: "var(--vs-fg-dim)" }}>加载中…</div>
      ) : (
        <div className="tree">
          <TreeNode node={tree} depth={0} />
        </div>
      )}
    </>
  );
}
