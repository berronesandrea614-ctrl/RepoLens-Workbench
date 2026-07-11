package com.repolens.service.impl.support;

import com.repolens.domain.entity.DependencyCheckEntity;

/**
 * 裁决优先级辅助类（纯函数，无副作用，直接可单测）。
 * <p>
 * 优先级（数字越小越紧急）：
 * MALICIOUS(1) &gt; TYPOSQUAT(2) &gt; NOT_FOUND(3) &gt; VULNERABLE(4) &gt; OK(5) &gt; UNKNOWN(6)
 * </p>
 */
public final class VerdictPriority {

    private VerdictPriority() {}

    /**
     * Returns the numeric priority of a verdict (lower = more urgent).
     * Unknown/null returns 99 so it is always overwritten by any known verdict.
     */
    public static int priorityOf(String verdict) {
        if (verdict == null) return 99;
        return switch (verdict) {
            case DependencyCheckEntity.VERDICT_MALICIOUS  -> 1;
            case DependencyCheckEntity.VERDICT_TYPOSQUAT  -> 2;
            case DependencyCheckEntity.VERDICT_NOT_FOUND  -> 3;
            case DependencyCheckEntity.VERDICT_VULNERABLE -> 4;
            case DependencyCheckEntity.VERDICT_OK         -> 5;
            default -> 6; // UNKNOWN or unrecognised
        };
    }

    /**
     * Returns the verdict with higher priority (lower number wins). Null-safe.
     * If both are null, returns null.
     */
    public static String merge(String v1, String v2) {
        if (v1 == null) return v2;
        if (v2 == null) return v1;
        return priorityOf(v1) <= priorityOf(v2) ? v1 : v2;
    }
}
