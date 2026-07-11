import { Panel, PanelGroup, PanelResizeHandle } from "react-resizable-panels";
import { TitleBar } from "./TitleBar";
import { ActivityBar } from "./ActivityBar";
import { Sidebar } from "./Sidebar";
import { EditorArea } from "./EditorArea";
import { StatusBar } from "./StatusBar";
import { EngineSwitcher } from "../features/claude/EngineSwitcher";
import { TerminalPanel } from "../features/terminal/TerminalPanel";
import { CommandPalette } from "../features/palette/CommandPalette";
import { useGlobalKeybindings } from "../hooks/useGlobalKeybindings";
import { useWorkbench } from "../state/workbenchStore";
import "./shell.css";

export function AppShell() {
  const terminalVisible = useWorkbench((s) => s.terminalVisible);
  const editorCollapsed = useWorkbench((s) => s.editorCollapsed);
  const { palette, closePalette } = useGlobalKeybindings();
  return (
    <div className="shell">
      <TitleBar />
      <div className="shell-body">
        <ActivityBar />
        <PanelGroup direction="horizontal" autoSaveId="repolens-h">
          <Panel defaultSize={18} minSize={5}>
            <Sidebar />
          </Panel>
          <PanelResizeHandle className="resize-handle-v" />
          {/* C3: middle editor panel — hidden when editorCollapsed */}
          {!editorCollapsed && (
            <>
              <Panel minSize={10}>
                <PanelGroup direction="vertical" autoSaveId="repolens-editor-v">
                  <Panel minSize={5}>
                    <EditorArea />
                  </Panel>
                  {terminalVisible && (
                    <>
                      <PanelResizeHandle className="resize-handle-h" />
                      <Panel defaultSize={30} minSize={5}>
                        <TerminalPanel />
                      </Panel>
                    </>
                  )}
                </PanelGroup>
              </Panel>
              <PanelResizeHandle className="resize-handle-v" />
            </>
          )}
          <Panel defaultSize={22} minSize={5}>
            <EngineSwitcher />
          </Panel>
        </PanelGroup>
      </div>
      <StatusBar />
      {palette && <CommandPalette mode={palette} onClose={closePalette} />}
    </div>
  );
}
