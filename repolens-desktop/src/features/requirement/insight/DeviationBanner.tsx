import { shouldShowDeviation } from "./insightUtils";
import type { InsightDeviation } from "./insightTypes";

interface DeviationBannerProps {
  deviation?: InsightDeviation | null;
  onJumpToOff?: () => void;
}

export function DeviationBanner({ deviation, onJumpToOff }: DeviationBannerProps) {
  if (!shouldShowDeviation(deviation)) return null;

  return (
    <div className="ins-deviation">
      <span className="ins-dev-ic">🚩</span>
      <span>
        <b>计划 vs 实际：</b>
        {deviation!.note}
      </span>
      {onJumpToOff && (
        <button className="ins-dev-act" onClick={onJumpToOff}>
          跳到该改动 ▾
        </button>
      )}
    </div>
  );
}
