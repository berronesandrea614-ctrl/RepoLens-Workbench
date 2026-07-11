import { useEffect, useState } from "react";
import { useWorkbench } from "../../state/workbenchStore";
import { ChatPanel } from "../chat/ChatPanel";
import { ClaudeCodePanel } from "./ClaudeCodePanel";
import { useI18n } from "../../i18n/I18nProvider";
import "./claude.css";

/**
 * EngineSwitcher — wraps the right panel with a two-tab switcher:
 *   💬 AI 会话  |  ⚡ Claude Code
 *
 * Switching only toggles CSS visibility; neither panel is unmounted after
 * its first activation (preserving terminal state / chat history).
 * ClaudeCodePanel is lazy-mounted: it isn't rendered at all until the user
 * first selects the Claude Code tab, avoiding a PTY spawn on cold start.
 */
export function EngineSwitcher() {
  const { t } = useI18n();
  const rightEngine = useWorkbench((s) => s.rightEngine);
  const setRightEngine = useWorkbench((s) => s.setRightEngine);

  // Track whether ClaudeCodePanel has ever been activated so we can keep it
  // in the DOM (hidden) after the user switches back to chat.
  const [claudeMounted, setClaudeMounted] = useState(rightEngine === "claude");

  useEffect(() => {
    if (rightEngine === "claude") setClaudeMounted(true);
  }, [rightEngine]);

  return (
    <div className="engine-switcher">
      <div className="engine-tab-bar">
        <button
          className={`engine-tab${rightEngine === "chat" ? " engine-tab--active" : ""}`}
          onClick={() => setRightEngine("chat")}
        >
          💬 {t("engine.chat", "AI 会话")}
        </button>
        <button
          className={`engine-tab${rightEngine === "claude" ? " engine-tab--active" : ""}`}
          onClick={() => setRightEngine("claude")}
        >
          ⚡ {t("engine.claude", "Claude Code")}
        </button>
      </div>

      {/* ChatPanel — always in the DOM, shown/hidden via CSS. */}
      <div
        className={`engine-panel${rightEngine === "chat" ? " engine-panel--visible" : ""}`}
      >
        <ChatPanel />
      </div>

      {/* ClaudeCodePanel — lazy-mounted on first activation, then kept alive. */}
      {claudeMounted && (
        <div
          className={`engine-panel${rightEngine === "claude" ? " engine-panel--visible" : ""}`}
        >
          <ClaudeCodePanel />
        </div>
      )}
    </div>
  );
}
