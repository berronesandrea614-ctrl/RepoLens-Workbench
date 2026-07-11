import type { SelfReport, SelfReportCheck } from "./reconciliationTypes";
import { getCheckIcon, getTrustBadge } from "./reconciliationUtils";

interface SelfReportBannerProps {
  selfReport: SelfReport;
}

const CHECK_LABELS: Record<string, string> = {
  FABRICATED_VERIFICATION: "声称已验证但无验证工具调用",
  CLAIM_CONTRADICTS_RESULT: "声明成功但验证结果非零/超时",
  TEST_WEAKENED: "测试断言数下降或新增 @Disabled",
  NO_OP_SUCCESS: "声明成功但无有效改动",
};

function CheckRow({ check }: { check: SelfReportCheck }) {
  return (
    <div className={`recon-check-row recon-check-${check.severity.toLowerCase()}`}>
      <span className="recon-check-icon">{getCheckIcon(check)}</span>
      <span className="recon-check-label">{CHECK_LABELS[check.type] ?? check.type}</span>
      {check.detail && (
        <span className="recon-check-detail">{check.detail}</span>
      )}
    </div>
  );
}

/**
 * 自报可信度展示区（仅有 checks 时展示细节）。
 */
export function SelfReportBanner({ selfReport }: SelfReportBannerProps) {
  const trust = getTrustBadge(selfReport.trustFlag);
  const checks = selfReport.checks ?? [];
  const isBad = selfReport.trustFlag === "FABRICATED" || selfReport.trustFlag === "SUSPECT";

  return (
    <div className={`recon-self-report${isBad ? " recon-self-report--warn" : ""}`}>
      <div className="recon-self-report-header">
        <span className="recon-self-report-title">自报可信度</span>
        <span className={`recon-trust-badge ${trust.cls}`}>{trust.label}</span>
        {selfReport.claimEvidence && (
          <span className="recon-self-evidence" title={selfReport.claimEvidence}>
            &quot;{selfReport.claimEvidence.slice(0, 60)}{selfReport.claimEvidence.length > 60 ? "…" : ""}&quot;
          </span>
        )}
      </div>
      {checks.length > 0 && (
        <div className="recon-checks" role="list">
          {checks.map((c, i) => (
            <CheckRow key={i} check={c} />
          ))}
        </div>
      )}
    </div>
  );
}
