package com.repolens.kernel.perm;

/**
 * 权限门 {@link KernelPermissionGate} 对一次工具调用的裁决结果。
 *
 * @param decision  ALLOW（真执行）/ ASK（需人工审批，当前非交互流暂拒）/ DENY（拒绝执行）
 * @param riskLevel 该调用的风险档位（透传前端可视化）
 * @param reason    人类可读的理由（透传前端；ALLOW 时也给一句简述）
 */
public record Decision(Verdict decision, RiskLevel riskLevel, String reason) {

    /** 裁决三态。 */
    public enum Verdict {
        ALLOW, ASK, DENY
    }

    public boolean isAllow() {
        return decision == Verdict.ALLOW;
    }

    public static Decision allow(RiskLevel level, String reason) {
        return new Decision(Verdict.ALLOW, level, reason);
    }

    public static Decision ask(RiskLevel level, String reason) {
        return new Decision(Verdict.ASK, level, reason);
    }

    public static Decision deny(RiskLevel level, String reason) {
        return new Decision(Verdict.DENY, level, reason);
    }
}
