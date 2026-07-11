package com.repolens.kernel.loop;

import com.repolens.llm.model.LlmMessage;

import java.util.List;

/**
 * 一次 agent run 的结果与可观测轨迹。
 *
 * <p>除最终文本外，还带出主循环的内部状态供前端可视化（规划 §3.7 横切铁律）：
 * 跑了几轮、共发起多少工具调用、因何终止、token 花销、完整消息历史。
 *
 * @param finalText          agent 的最终答复（无 tool_call 那轮的 content）
 * @param turns              主循环执行了多少轮
 * @param toolCallCount      累计发起的工具调用数
 * @param terminationReason  终止原因（见 {@link TerminationReason}）
 * @param tokensSpent        本 run 估算消耗的 token
 * @param transcript         完整消息历史（system 之外的 user/assistant/tool）
 */
public record AgentRunResult(
        String finalText,
        int turns,
        int toolCallCount,
        TerminationReason terminationReason,
        long tokensSpent,
        List<LlmMessage> transcript) {

    /** 主循环终止原因。 */
    public enum TerminationReason {
        /** 正常终止：LLM 不再请求工具（stop_reason≠tool_use）——这是唯一「自然完成」信号。 */
        NO_TOOL_CALL,
        /** token 或墙钟预算耗尽。 */
        BUDGET_EXHAUSTED,
        /** LLM 调用失败且无法继续。 */
        LLM_ERROR
    }
}
