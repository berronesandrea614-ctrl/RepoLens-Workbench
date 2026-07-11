package com.repolens.domain.enums;

public enum PermissionMode {
    /**
     * 默认：写工具 STAGE（人工审批），读工具 ALLOW。
     */
    DEFAULT,
    /**
     * 计划模式：只注入只读工具，不注入写/执行工具。
     */
    PLAN,
    /**
     * 接受编辑：普通编辑 AUTO_APPLY；deleteFile/EXEC 仍 STAGE（破坏性仍拦）。
     */
    ACCEPT_EDITS,
    /**
     * 自动模式：RiskClassifier A/B/C → AUTO_APPLY；E → BLOCK；D → ASK。
     */
    AUTO,
    /**
     * 旁路（调试用）：所有操作 AUTO_APPLY，不建议生产使用。
     */
    BYPASS
}
