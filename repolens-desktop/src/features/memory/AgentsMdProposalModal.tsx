import { useEffect, useState } from "react";
import { DiffEditor } from "@monaco-editor/react";
import { getAgentsMdProposal } from "../../api/governanceApi";
import type { AgentsMdProposal } from "../../api/governanceApi";
import { useWorkbench } from "../../state/workbenchStore";

interface Props {
  onClose: () => void;
}

/**
 * AGENTS.md 增补提案 Modal。
 *
 * 拉取 GET /governance/agents-md/proposal，用 Monaco DiffEditor 展示
 * currentContent（左）vs proposedContent（右）。提案仅供参考，不会落盘。
 * hasChanges=false 时显示"无新增约定"。
 */
export function AgentsMdProposalModal({ onClose }: Props) {
  const repoId = useWorkbench((s) => s.repoId);
  const [proposal, setProposal] = useState<AgentsMdProposal | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!repoId) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);
    getAgentsMdProposal(repoId)
      .then((p) => {
        setProposal(p);
      })
      .catch((err: unknown) => {
        setError(String(err));
      })
      .finally(() => {
        setLoading(false);
      });
  }, [repoId]);

  return (
    <div className="diff-overlay" onClick={onClose}>
      <div className="diff-modal" onClick={(e) => e.stopPropagation()}>
        {/* 标题栏 */}
        <div className="diff-modal-head">
          <span className="diff-modal-title" title="AGENTS.md 增补提案">
            AGENTS.md — 增补提案预览
          </span>
          <button className="diff-modal-close" onClick={onClose} title="关闭">
            ✕
          </button>
        </div>

        {/* 内容区 */}
        <div className="diff-modal-body">
          {loading && <div className="diff-modal-loading">加载 AGENTS.md 增补提案…</div>}

          {!loading && error && (
            <div style={{ padding: 24, color: "#f04747", fontSize: 13 }}>{error}</div>
          )}

          {!loading && !error && proposal && !proposal.hasChanges && (
            <div
              style={{
                display: "flex",
                alignItems: "center",
                justifyContent: "center",
                height: "100%",
                color: "#8b949e",
                fontSize: 14,
              }}
            >
              无新增约定
            </div>
          )}

          {!loading && !error && proposal && proposal.hasChanges && (
            <DiffEditor
              original={proposal.currentContent}
              modified={proposal.proposedContent}
              language="markdown"
              theme="vs-dark"
              height="100%"
              options={{
                readOnly: true,
                renderSideBySide: true,
                minimap: { enabled: false },
                scrollBeyondLastLine: false,
                automaticLayout: true,
              }}
            />
          )}
        </div>

        {/* 页脚提示 */}
        <div className="agents-md-footer">
          提案仅供参考，不会自动写入；请手动复制到 AGENTS.md
        </div>
      </div>
    </div>
  );
}
