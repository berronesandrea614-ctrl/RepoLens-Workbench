package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 一次 agent 执行的完整可回放 trace：run 头 + 逐步轨迹。
 */
@Data
@Builder
public class AgentRunTraceVO {

    private AgentRunVO run;
    private List<AgentRunStepVO> steps;
}
