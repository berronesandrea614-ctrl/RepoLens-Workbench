package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次 agent 执行记录的列表视图，供前端渲染 run 列表并选择打开哪条 trace。
 */
@Data
@Builder
public class AgentRunVO {

    private Long id;
    private String question;
    private String mode;
    private Integer iterations;
    private Integer toolCalls;
    private String status;
    private LocalDateTime createdAt;
    /** 该 run 的步数（agent_run_step 行数）。 */
    private Integer stepCount;
    private Long sessionId;
}
