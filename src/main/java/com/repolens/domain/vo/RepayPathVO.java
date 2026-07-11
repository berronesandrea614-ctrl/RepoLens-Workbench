package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 偿债路径 VO（MVP 层：理由卡片 + 长期记忆，Claude 测验留 P1 占位）。
 */
@Data
@Builder
public class RepayPathVO {

    private Long   fileId;
    private String filePath;

    /** 现成理由卡片（来自 requirement.approach / agent_run_plan.plan_json）。 */
    private List<String> rationales;

    /** 相关长期记忆摘要（来自 AgentMemoryService.recall）。 */
    private List<String> memories;

    /** MVP 占位：是否支持让 Claude 出测验（P1 实现，当前固定 false）。 */
    private boolean canAskClaude;

    /** Claude 理解测验预置 Prompt（P1 实现时填入，供前端一键触发）。 */
    private String suggestedPrompt;

    /** 当前综合债务分（用于偿债前后对比展示）。 */
    private int currentScore;

    /** 当前分档。 */
    private String currentBand;
}
