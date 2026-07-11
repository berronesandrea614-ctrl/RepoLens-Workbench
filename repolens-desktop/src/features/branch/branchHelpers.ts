import { BranchNodeMetrics, BranchStatus } from "../../api/branchApi";

// ── Pure helpers — unit-testable, no React/DOM deps ───────────────

export interface StatusStyle {
  borderColor: string;
  opacity: number;
  animationName?: string;
}

/** Map branch status to border colour, opacity and optional animation. */
export function statusStyle(status: BranchStatus): StatusStyle {
  switch (status) {
    case "GENERATING":
      return { borderColor: "#58a6ff", opacity: 1, animationName: "branchPulse" };
    case "READY":
      return { borderColor: "#6e7681", opacity: 1 };
    case "SELECTED":
      return { borderColor: "#3fb950", opacity: 1 };
    case "DISCARDED":
      return { borderColor: "#3d444d", opacity: 0.45 };
  }
}

export interface FormattedMetrics {
  files: string;
  blast: string;
  debt: string;
  confidence: string;
}

/** Format raw metric numbers into display strings. */
export function formatMetrics(metrics: BranchNodeMetrics): FormattedMetrics {
  const { filesChanged, blastRadiusSize, debtDelta, confidence } = metrics;
  return {
    files: String(filesChanged),
    blast: String(blastRadiusSize),
    debt: debtDelta > 0 ? `+${debtDelta}` : String(debtDelta),
    confidence: `${confidence}%`,
  };
}

/** Clamp variant count to [2, 4]. */
export function clampVariantCount(n: number): number {
  return Math.min(Math.max(Math.round(n), 2), 4);
}
