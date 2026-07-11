package com.repolens.kernel.hook;

import com.repolens.kernel.spi.ToolContext;

import java.util.Map;

/**
 * PostToolUse Hook（M7.1）：在工具<b>执行完成之后</b>被调，用于观察/记录/审计，<b>不改结果</b>。
 *
 * <p>接入点：{@link com.repolens.kernel.loop.ToolTurnScheduler} 在 dispatch 拿到结果后依次调用所有
 * PostToolUse Hook。为保持「无 tool_call 即止」「写路径单线程」等既有语义不被破坏，本时机<b>只旁路观察</b>，
 * 返回值不影响回填给 LLM 的 observation。
 */
public interface PostToolUseHook {

    /**
     * @param toolName    执行过的工具名
     * @param args        实际执行时的参数（若被 PreToolUse 改写，则为改写后的）
     * @param observation 工具产出的结果文本（回填给 LLM 的那份）
     * @param ctx         执行上下文
     */
    void afterToolUse(String toolName, Map<String, Object> args, String observation, ToolContext ctx);
}
