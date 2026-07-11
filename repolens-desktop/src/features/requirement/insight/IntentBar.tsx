import { buildChipLabels } from "./insightUtils";
import type { InsightChips } from "./insightTypes";

interface IntentBarProps {
  intent: string;
  approach?: string;
  chips?: InsightChips | null;
  isDegrade?: boolean;
  degradeNote?: string;
}

export function IntentBar({ intent, approach, chips, isDegrade, degradeNote }: IntentBarProps) {
  const chipLabels = buildChipLabels(chips ?? null);

  return (
    <>
      <div className="ins-intentbar">
        <div className="ins-row">
          <span className="ins-q">🎯 你要的：</span>
          {intent}
        </div>
        {approach && (
          <div className="ins-row">
            <span className="ins-plan-label">🧠 AI 整体思路：</span>
            <span className="ins-plan-label">{approach}</span>
          </div>
        )}
        <div className="ins-chips">
          {isDegrade ? (
            <>
              <span className="ins-chip">💬 纯问答需求</span>
              <span className="ins-chip">未改动任何代码</span>
            </>
          ) : (
            chipLabels.map((c, i) => (
              <span key={i} className={`ins-chip ${c.cls}`}>
                {c.text}
              </span>
            ))
          )}
        </div>
      </div>
      {degradeNote && <div className="ins-degrade">{degradeNote}</div>}
    </>
  );
}
