package com.repolens.kernel.hook;

import com.repolens.kernel.spi.ToolContext;

import java.util.Map;

/**
 * PreToolUse Hook（M7.1）：在工具<b>真执行之前</b>被调，可 BLOCK（拒绝）或改参（返回改写后的 args）。
 *
 * <p>接入点：{@link com.repolens.kernel.loop.ToolTurnScheduler} 在<b>权限门放行之后、dispatch 之前</b>
 * 依次调用所有 PreToolUse Hook。第一个返回 BLOCK 的短路（工具不执行）；返回 MODIFY 的把新参数作为
 * 后续 hook / dispatch 的输入（可链式改写）。
 *
 * <p>确定性：Hook 用固定规则判断，不走 LLM——这是与权限门配合的「硬编码护栏」层。
 */
public interface PreToolUseHook {

    /**
     * @param toolName 即将执行的工具名
     * @param args     当前（可能已被前序 hook 改写过的）参数
     * @param ctx      执行上下文
     * @return 裁决（proceed / proceedWith / block）
     */
    HookDecision beforeToolUse(String toolName, Map<String, Object> args, ToolContext ctx);
}
