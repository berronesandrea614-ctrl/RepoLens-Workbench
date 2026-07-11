package com.repolens.kernel.route;

/**
 * LLM 调用的用途分档（M7.3 多档模型路由）。不同用途可路由到不同档位的模型：
 * 主推理用强模型，杂活（子代理摘要、未来的 compaction 摘要等）用小模型省成本。
 */
public enum LlmCallKind {

    /** 主推理：agent 主循环的核心决策，需强模型。 */
    MAIN_REASONING,

    /** 杂活：子代理 Task 调研摘要、未来的 compaction 摘要等，可用小模型。 */
    CHORE
}
