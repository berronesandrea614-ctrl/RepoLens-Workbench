package com.repolens.kernel.spi;

import com.repolens.llm.model.ToolDefinition;

import java.util.Map;

/**
 * 内核工具统一接口——「工具注册表模式」的基石（规划 §2）。
 *
 * <p>旧铁律「只在 {@code doInvoke} 巨型 switch 里加工具」在重写版被改造为注册表：
 * 每个工具是一个实现本接口的 handler，{@code ToolRouter} 按 {@link #name()} 查表分发。
 * 加工具 = 加一个 handler 类 + 注册，不再改 1476 行的 god switch。
 *
 * <p>实现约定：
 * <ul>
 *   <li>{@link #execute} 返回喂回给 LLM 的 tool_result 文本，<b>不抛异常</b>——
 *       工具内部错误应捕获后以自然语言错误串返回（fail-safe，让 agent 读错自愈，而非崩掉 loop）；</li>
 *   <li>{@link #readOnly()} 决定调度策略：只读工具可并发，写类工具串行（规划 §3.1 写路径单线程）。</li>
 * </ul>
 */
public interface KernelTool {

    /** 工具名（LLM function-calling 里的 name，全局唯一）。 */
    String name();

    /** 是否只读：只读工具由 {@code ToolTurnScheduler} 并发执行；写类工具串行。 */
    boolean readOnly();

    /** 暴露给 LLM 的工具定义（name/description/JSON-Schema 参数）。 */
    ToolDefinition definition();

    /**
     * 执行工具调用。
     *
     * @param ctx  执行上下文（repo/会话/影子区/读记账本）
     * @param args LLM 给出的实参（已解析为 Map）
     * @return 喂回 LLM 的 tool_result 文本；失败也以文本形式返回，不抛异常
     */
    String execute(ToolContext ctx, Map<String, Object> args);
}
