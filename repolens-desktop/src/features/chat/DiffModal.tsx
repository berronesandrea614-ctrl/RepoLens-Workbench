import { DiffEditor } from "@monaco-editor/react";
import { languageFromPath } from "../../api/fileApi";

interface DiffModalProps {
  filePath: string;
  oldContent: string;
  newContent: string;
  loading?: boolean;
  onClose: () => void;
}

/** 只读 diff 预览：左侧 = AI 改动前，右侧 = 改动后。铺满遮罩层，便于阅读。 */
export function DiffModal({ filePath, oldContent, newContent, loading, onClose }: DiffModalProps) {
  return (
    <div className="diff-overlay" onClick={onClose}>
      <div className="diff-modal" onClick={(e) => e.stopPropagation()}>
        <div className="diff-modal-head">
          <span className="diff-modal-title" title={filePath}>{filePath}</span>
          <button className="diff-modal-close" onClick={onClose} title="关闭">✕</button>
        </div>
        <div className="diff-modal-body">
          {loading ? (
            <div className="diff-modal-loading">加载改动详情…</div>
          ) : (
            <DiffEditor
              original={oldContent}
              modified={newContent}
              language={languageFromPath(filePath)}
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
      </div>
    </div>
  );
}
