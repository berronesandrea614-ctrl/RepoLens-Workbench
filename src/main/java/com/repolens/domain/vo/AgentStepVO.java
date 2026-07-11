package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

/**
 * Agent 执行轨迹中的一步，用于前端可视化“思考 → 工具 → 观察”过程。
 * 每次 LLM 发起工具调用即产生一步。
 */
@Data
@Builder
public class AgentStepVO {

    /** 第几步，从 1 开始。 */
    private Integer stepIndex;

    /** 本步模型的思考文本（assistant 在发起工具调用前的 content，可能为空）。 */
    private String thought;

    /** 调用的工具名。 */
    private String toolName;

    /** 调用参数（紧凑 JSON 文本）。 */
    private String toolArgs;

    /** 工具返回的观察摘要（截断后的结果，用于前端展示，不含完整大文本）。 */
    private String observation;

    /** 本步从工具结果中提取到的新证据条数。 */
    private Integer discoveredCount;

    private String permissionVerdict;
    private String applyStrategy;
    private String riskLevel;
}
