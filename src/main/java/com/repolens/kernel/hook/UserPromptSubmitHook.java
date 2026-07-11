package com.repolens.kernel.hook;

import com.repolens.kernel.spi.ToolContext;

/**
 * UserPromptSubmit Hook（M7.1）：用户输入<b>进入 loop 之前</b>被调，可校验/增强 prompt。
 *
 * <p>接入点：{@link com.repolens.kernel.loop.AgentLoopExecutor#run} 在把 userPrompt 组装成首条消息之前
 * 依次调用所有 UserPromptSubmit Hook，链式改写。返回 null / 空白表示不改写（沿用上一步的文本）。
 *
 * <p>刻意保持极简：只做「文本进 → 文本出」的确定性改写，不做拦截（拦截语义留给 PreToolUse / 权限门），
 * 避免破坏 loop 的入口契约。
 */
public interface UserPromptSubmitHook {

    /**
     * @param prompt 当前（可能已被前序 hook 改写过的）用户输入
     * @param ctx    执行上下文（可空——loop 入口尚未必然带 ctx 时兜底）
     * @return 改写后的 prompt；返回 null 表示不改写
     */
    String onUserPromptSubmit(String prompt, ToolContext ctx);
}
