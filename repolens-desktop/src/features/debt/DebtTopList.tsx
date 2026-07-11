import { DebtUnit } from "../../types/debt";
import { useWorkbench } from "../../state/workbenchStore";

interface Props {
  items: DebtUnit[];
  activeFileId: number | null;
  onSelect: (unit: DebtUnit) => void;
}

function bandClass(band: string): string {
  if (band === "RED") return "red";
  if (band === "YELLOW") return "yellow";
  return "green";
}

function baseName(filePath: string): string {
  const parts = filePath.split(/[/\\]/);
  return parts[parts.length - 1] ?? filePath;
}

/**
 * 债务 Top 列表：按 score 降序，高危 (RED) 在顶部。
 */
export function DebtTopList({ items, activeFileId, onSelect }: Props) {
  const openFile = useWorkbench((s) => s.openFile);
  if (items.length === 0) {
    return (
      <div className="debt-top-list">
        <div style={{ padding: "12px", color: "#8b949e", fontSize: "12px" }}>
          暂无高于阈值的债务条目（minScore=40）
        </div>
      </div>
    );
  }

  return (
    <div className="debt-top-list">
      {items.map((item) => (
        <div
          key={item.fileId}
          className={`debt-item${activeFileId === item.fileId ? " active" : ""}`}
          onClick={() => onSelect(item)}
        >
          <div className="debt-item-header">
            <span className="debt-item-name">{baseName(item.filePath)}</span>
            <span className={`debt-score-badge ${bandClass(item.band)}`}>
              {item.score}
            </span>
          </div>
          <div
            className="debt-item-path"
            title={`打开 ${item.filePath}`}
            onClick={(e) => {
              e.stopPropagation();
              openFile(item.filePath);
            }}
            style={{ cursor: "pointer" }}
          >
            {item.filePath}
          </div>
        </div>
      ))}
    </div>
  );
}
