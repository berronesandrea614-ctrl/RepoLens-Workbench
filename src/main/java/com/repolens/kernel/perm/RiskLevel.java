package com.repolens.kernel.perm;

/**
 * 单次工具调用的风险分级（M4 权限体系的度量单位）。A 最低、E 最高。
 *
 * <ul>
 *   <li>{@code A} 只读检索：read/grep/glob——无副作用，永不改状态；</li>
 *   <li>{@code B} 只读验证：runVerification——在影子区真跑编译/测试，重但无破坏；</li>
 *   <li>{@code C} 隔离写：write/edit/multi_edit——只落影子区（已隔离），或普通 bash；</li>
 *   <li>{@code D} 需留意的 bash：被安全检查 STEER 或语义不明的命令；</li>
 *   <li>{@code E} 破坏性：被 {@code CommandSafetyChecker} DENY 的系统级命令（rm -rf /、mkfs、git push…）。</li>
 * </ul>
 */
public enum RiskLevel {
    A, B, C, D, E
}
