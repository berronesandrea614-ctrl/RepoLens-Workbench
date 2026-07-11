package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CodeAnswerVO {

    private Long repoId;
    private Long sessionId;
    private String question;
    private String answer;
    private Boolean degraded;
    private String degradeReason;
    private List<CodeReferenceVO> references;
    private String modelName;
    private Integer promptTokens;
    private Integer completionTokens;
    private Long costMs;

    /** 是否走 agentic 多步检索（true=agent 模式，false=单轮 RAG）。 */
    private Boolean agentMode;

    /** agent 执行轨迹（思考-工具-观察），单轮模式为空。供前端可视化。 */
    private List<AgentStepVO> agentSteps;

    /** agent 实际迭代轮数。 */
    private Integer agentIterations;

    /** agent 累计工具调用次数。 */
    private Integer agentToolCalls;

    /** 编码模式本轮产生的文件变更（ask 模式为空）。供前端展示改动与一键 revert。 */
    private List<FileChangeVO> fileChanges;

    /** 本轮 agent 执行记录的 id（agent 模式已落库时非空），供前端打开对应可回放 trace。 */
    private Long agentRunId;

    /** 验证运行摘要 */
    private List<VerificationSummary> verifications;
    /** 特性清单摘要 */
    private List<FeatureSummary> features;
    /** 是否还有未通过验证的特性 */
    private Boolean hasUnfinishedFeatures;
    /** 影子区是否活跃 */
    private Boolean shadowActive;

    @Data
    public static class VerificationSummary {
        private String kind;
        private Boolean passed;
        private Integer exitCode;
        private List<String> failureMessages;
    }

    @Data
    public static class FeatureSummary {
        private String id;
        private String description;
        private String status;
    }
}
