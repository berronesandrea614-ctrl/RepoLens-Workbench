import { useState } from "react";
import { CodeReference } from "../../types/chat";
import { CodePreview } from "./CodePreview";
import { useWorkbench } from "../../state/workbenchStore";

export function ReferenceCard({ reference, index }: { reference: CodeReference; index?: number }) {
  const [expanded, setExpanded] = useState(false);
  const openFile = useWorkbench((s) => s.openFile);
  const fileName = reference.filePath.split("/").pop();

  return (
    <div style={{ border: "1px solid var(--vs-border)", borderRadius: 6, marginBottom: 8,
      background: "var(--vs-sidebar)" }}>
      <div style={{ display: "flex", gap: 8, alignItems: "center", padding: "6px 10px", flexWrap: "wrap" }}>
        {index != null && <span style={badge}>{index + 1}</span>}
        <span
          role="button"
          tabIndex={0}
          onClick={() => openFile(reference.filePath, reference.startLine ?? 1)}
          onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); openFile(reference.filePath, reference.startLine ?? 1); } }}
          title={reference.filePath}
          style={{ cursor: "pointer", color: "#4daafc", fontWeight: 600 }}
        >
          {fileName}
        </span>
        <span style={tag}>{reference.chunkType}</span>
        <span style={tag}>L{reference.startLine}-{reference.endLine}</span>
        <span style={{ ...tag, color: "#7ee787" }}>{reference.score?.toFixed(3)}</span>
        {reference.className && (
          <span style={tag}>{reference.className}{reference.methodName ? `#${reference.methodName}` : ""}</span>
        )}
      </div>
      {reference.contentPreview ? (
        <div style={{ padding: "0 8px 8px" }}>
          <CodePreview
            code={reference.contentPreview}
            filePath={reference.filePath}
            startLine={reference.startLine}
            maxHeight={expanded ? undefined : 160}
          />
          <button onClick={() => setExpanded(!expanded)} style={linkBtn}>
            {expanded ? "收起" : "展开全部"}
          </button>
        </div>
      ) : (
        <div style={{ padding: "0 10px 8px", color: "var(--vs-fg-dim)" }}>无内容预览</div>
      )}
    </div>
  );
}

const badge: React.CSSProperties = { background: "#1f6feb", color: "#fff", borderRadius: 10,
  padding: "0 7px", fontSize: 11 };
const tag: React.CSSProperties = { background: "#30363d", color: "#c9d1d9", borderRadius: 4,
  padding: "1px 6px", fontSize: 11 };
const linkBtn: React.CSSProperties = { background: "none", border: "none", color: "#4daafc",
  cursor: "pointer", fontSize: 12, padding: "4px 0" };
