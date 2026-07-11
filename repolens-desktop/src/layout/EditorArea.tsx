import { Panel, PanelGroup, PanelResizeHandle } from "react-resizable-panels";
import { EditorTabs } from "../features/editor/EditorTabs";
import { Breadcrumbs } from "../features/editor/Breadcrumbs";
import { MonacoEditor } from "../features/editor/MonacoEditor";
import { GraphView } from "../features/graph/GraphView";
import { RequirementGraphView } from "../features/graph/RequirementGraphView";
import { AgentRunView } from "../features/agentrun/AgentRunView";
import { SettingsView } from "../features/settings/SettingsView";
import { DebtView } from "../features/debt/DebtView";
import { ProvenanceView } from "../features/provenance/ProvenanceView";
import { TraceabilityView } from "../features/traceability/TraceabilityView";
import { AdrView } from "../features/memory/AdrView";
import { SensitiveFileView } from "../features/memory/SensitiveFileView";
import { MissionControlBoard } from "../features/mission/MissionControlBoard";
import { TimelineView } from "../features/timeline/TimelineView";
import { DriftView } from "../features/drift/DriftView";
import { BranchGraphView } from "../features/branch/BranchGraphView";
import { EgressMonitorPanel } from "../features/egress/EgressMonitorPanel";
import { WelcomeView } from "../features/explorer/WelcomeView";
import { useWorkbench } from "../state/workbenchStore";

function EditorGroupPane({ groupIndex }: { groupIndex: number }) {
  const isActive = useWorkbench((s) => s.activeGroupIndex === groupIndex);
  const multi = useWorkbench((s) => s.groups.length > 1);
  const setActiveGroup = useWorkbench((s) => s.setActiveGroup);
  return (
    <div
      className={`editor-group ${multi && isActive ? "active-group" : ""}`}
      onMouseDown={() => setActiveGroup(groupIndex)}
    >
      <EditorTabs groupIndex={groupIndex} />
      {!multi && <Breadcrumbs />}
      <div style={{ flex: 1, minHeight: 0, display: "flex", flexDirection: "column" }}>
        <MonacoEditor groupIndex={groupIndex} />
      </div>
    </div>
  );
}

export function EditorArea() {
  const view = useWorkbench((s) => s.view);
  const repoId = useWorkbench((s) => s.repoId);
  const groupCount = useWorkbench((s) => s.groups.length);
  // 设置页与仓库无关，冷启动时也可访问；其余仓库相关视图在未打开仓库时显示欢迎页。
  if (view === "settings") {
    return <div className="editor"><SettingsView /></div>;
  }
  // 出网监控与仓库无关，冷启动时也可访问（同设置页）。
  if (view === "egress") {
    return <div className="editor"><EgressMonitorPanel /></div>;
  }
  if (repoId == null) {
    return <div className="editor"><WelcomeView /></div>;
  }
  if (view === "graph") {
    return <div className="editor"><GraphView /></div>;
  }
  if (view === "requirements") {
    return <div className="editor"><RequirementGraphView /></div>;
  }
  if (view === "agentruns") {
    return <div className="editor"><AgentRunView /></div>;
  }
  if (view === "debt") {
    return <div className="editor"><DebtView /></div>;
  }
  if (view === "provenance") {
    return <div className="editor"><ProvenanceView /></div>;
  }
  if (view === "traceability") {
    return <div className="editor"><TraceabilityView /></div>;
  }
  if (view === "adr") {
    return <div className="editor"><AdrView /></div>;
  }
  if (view === "sensitive") {
    return <div className="editor"><SensitiveFileView /></div>;
  }
  if (view === "mission") {
    return <div className="editor"><MissionControlBoard /></div>;
  }
  if (view === "timeline") {
    return <div className="editor"><TimelineView /></div>;
  }
  if (view === "drift") {
    return <div className="editor"><DriftView /></div>;
  }
  if (view === "branch") {
    return <div className="editor"><BranchGraphView /></div>;
  }
  if (groupCount === 2) {
    return (
      <div className="editor">
        <PanelGroup direction="horizontal" autoSaveId="repolens-editor-groups">
          <Panel minSize={20}>
            <EditorGroupPane groupIndex={0} />
          </Panel>
          <PanelResizeHandle className="resize-handle-v" />
          <Panel minSize={20}>
            <EditorGroupPane groupIndex={1} />
          </Panel>
        </PanelGroup>
      </div>
    );
  }
  return (
    <div className="editor">
      <EditorGroupPane groupIndex={0} />
    </div>
  );
}
