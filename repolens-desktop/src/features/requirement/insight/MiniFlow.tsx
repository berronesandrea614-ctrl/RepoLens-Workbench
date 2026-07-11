import React from "react";
import { FlowItem, FlowNode } from "./insightTypes";
import { isFlowNode, getNodeAction } from "./insightUtils";

interface MiniFlowProps {
  flow: FlowItem[];
  onNodeDiff?: (node: FlowNode) => void;
  onNodeOpenFile?: (node: FlowNode) => void;
}

function FlowNodeCard({
  node,
  onNodeDiff,
  onNodeOpenFile,
}: {
  node: FlowNode;
  onNodeDiff?: (n: FlowNode) => void;
  onNodeOpenFile?: (n: FlowNode) => void;
}) {
  const action = getNodeAction(node);
  const clickable = action !== "none";

  function handleClick(e: React.MouseEvent) {
    e.stopPropagation();
    if (!clickable) return;
    if (action === "diff" && onNodeDiff) onNodeDiff(node);
    else if (action === "openFile" && onNodeOpenFile) onNodeOpenFile(node);
  }

  const cls = ["ins-fnode", node.cls ?? "", clickable ? "clickable" : ""]
    .filter(Boolean)
    .join(" ");

  return (
    <div className={cls} onClick={handleClick}>
      {node.tag && <span className="ins-fn-tag">{node.tag}</span>}
      <div className="ins-fn-top">
        <span className="ins-fn-role">
          <i className="ins-fn-ic">{(node.name ?? "?")[0]}</i>
          {node.role ?? ""}
        </span>
        {node.delta && <span className="ins-fn-delta">{node.delta}</span>}
      </div>
      <div className="ins-fn-name">{node.name ?? ""}</div>
      {node.sig && <div className="ins-fn-sig">{node.sig}</div>}
      {node.note && <div className="ins-fn-note">{node.note}</div>}
    </div>
  );
}

function FlowEdgeEl({ data, mut }: { data?: string; mut?: boolean }) {
  return (
    <div className={`ins-fedge${mut ? " mut" : ""}`}>
      {data && <div className="ins-e-data">{data}</div>}
      <div className="ins-e-track">
        <span className="ins-e-dot" />
      </div>
    </div>
  );
}

export function MiniFlow({ flow, onNodeDiff, onNodeOpenFile }: MiniFlowProps) {
  return (
    <div className="ins-flowscroll">
      <div className="ins-flow">
        {flow.map((item, idx) =>
          isFlowNode(item) ? (
            <FlowNodeCard
              key={idx}
              node={item}
              onNodeDiff={onNodeDiff}
              onNodeOpenFile={onNodeOpenFile}
            />
          ) : (
            <FlowEdgeEl key={idx} data={item.data} mut={item.mut} />
          ),
        )}
      </div>
    </div>
  );
}
