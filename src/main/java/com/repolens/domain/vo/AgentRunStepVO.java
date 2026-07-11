package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * agent trace 中的一步，供前端渲染 timeline + 因果 DAG 的单个节点。
 */
@Data
@Builder
public class AgentRunStepVO {

    private Long id;
    private Integer stepIndex;
    /** THINK / TOOL / WRITE。 */
    private String type;
    private String toolName;
    private String toolArgs;
    private String thought;
    /** 观察结果的传输摘要（截断后）。 */
    private String observationSummary;
    /** 本步触达的文件路径列表。 */
    private List<String> targetFiles;
    private String status;
}
