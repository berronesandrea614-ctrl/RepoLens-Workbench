package com.repolens.kernel.hook;

import java.util.Map;

/**
 * PreToolUse Hook 的裁决结果（M7.1 确定性控制层）。
 *
 * <p>Hook 是<b>确定性</b>控制层，与概率性的权限门（M4）互补：权限门按风险档位/模式做<b>策略</b>裁决，
 * Hook 则按<b>确定规则</b>在工具执行前做最后一道拦截或改写（如「密钥路径一律不许写」）。
 *
 * <p>三态：
 * <ul>
 *   <li>{@link #proceed()} —— 放行，参数不变；</li>
 *   <li>{@link #proceedWith(Map)} —— 放行，但用返回的新参数<b>替换</b>原参数（改参）；</li>
 *   <li>{@link #block(String)} —— 拦截，工具不执行，理由回填给 LLM 当 observation。</li>
 * </ul>
 *
 * @param outcome      裁决（PROCEED / MODIFY / BLOCK）
 * @param rewrittenArgs MODIFY 时的新参数（其余情况为 null）
 * @param reason       BLOCK 时的拦截理由（其余情况可空）
 */
public record HookDecision(Outcome outcome, Map<String, Object> rewrittenArgs, String reason) {

    public enum Outcome { PROCEED, MODIFY, BLOCK }

    /** 放行且不改参。 */
    public static HookDecision proceed() {
        return new HookDecision(Outcome.PROCEED, null, null);
    }

    /** 放行但用新参数替换原参数。 */
    public static HookDecision proceedWith(Map<String, Object> rewrittenArgs) {
        return new HookDecision(Outcome.MODIFY, rewrittenArgs, null);
    }

    /** 拦截：工具不执行，reason 作为 observation 回填。 */
    public static HookDecision block(String reason) {
        return new HookDecision(Outcome.BLOCK, null, reason);
    }

    public boolean isBlock() {
        return outcome == Outcome.BLOCK;
    }

    public boolean isModify() {
        return outcome == Outcome.MODIFY && rewrittenArgs != null;
    }
}
