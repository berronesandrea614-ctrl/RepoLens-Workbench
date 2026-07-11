import { useWorkbench } from "../../state/workbenchStore";
import { iconForFile } from "../../ui/fileIcons";

export function Breadcrumbs() {
  const path = useWorkbench((s) => s.activePath);
  if (!path) return null;
  const parts = path.split("/");
  const file = parts[parts.length - 1];
  const { icon, color } = iconForFile(file);
  return (
    <div className="breadcrumbs">
      {parts.slice(0, -1).map((p, i) => (
        <span key={i} className="crumb">
          {p}<span className="codicon codicon-chevron-right" />
        </span>
      ))}
      <span className="crumb file">
        <span className={`codicon ${icon}`} style={{ color }} /> {file}
      </span>
    </div>
  );
}
