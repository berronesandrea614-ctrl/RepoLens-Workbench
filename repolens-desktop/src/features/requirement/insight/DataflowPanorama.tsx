import { PanoramaData, FlowNode } from "./insightTypes";
import { MiniFlow } from "./MiniFlow";

interface DataflowPanoramaProps {
  panorama?: PanoramaData | null;
  hasChanges: boolean;
  onNodeDiff?: (node: FlowNode) => void;
  onNodeOpenFile?: (node: FlowNode, line?: number) => void;
}

export function DataflowPanorama({
  panorama,
  hasChanges,
  onNodeDiff,
  onNodeOpenFile,
}: DataflowPanoramaProps) {
  if (!hasChanges) {
    return (
      <div className="ins-pano">
        <p className="ins-pano-hint">
          💬 该需求未改动代码，无数据流全景可展示。
        </p>
      </div>
    );
  }

  if (!panorama || !panorama.layers || panorama.layers.length === 0) {
    return (
      <div className="ins-pano">
        <p className="ins-pano-hint">
          该需求暂无分层全景数据。
        </p>
      </div>
    );
  }

  return (
    <div className="ins-pano">
      <p className="ins-pano-hint">
        整个需求的端到端数据流（分层：请求入口 → 业务服务 → 存储/外部）。
        本次 AI 触碰/改过的路径高亮，未触碰的灰化。
      </p>
      {panorama.layers.map((layer, i) => (
        <div key={i}>
          <div className="ins-plane">
            <div className="ins-pl">{layer.label}</div>
            <MiniFlow
              flow={layer.flow}
              onNodeDiff={onNodeDiff}
              onNodeOpenFile={
                onNodeOpenFile ? (n) => onNodeOpenFile(n, n.startLine) : undefined
              }
            />
          </div>
          {i < panorama.layers.length - 1 && (
            <div className="ins-lanedown">↓</div>
          )}
        </div>
      ))}
    </div>
  );
}
