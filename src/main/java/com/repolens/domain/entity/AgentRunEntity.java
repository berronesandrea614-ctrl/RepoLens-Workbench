package com.repolens.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次 agent 多步问答的可回放执行记录（trace 的头）。
 * 每次走 agent loop 的问答落一条，配套 {@link AgentRunStepEntity} 记录逐步轨迹，
 * 供前端把"可视化主语从 repo 换成 agent run"，渲染 timeline + 因果 DAG。
 */
@Data
@TableName("agent_run")
public class AgentRunEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long repoId;

    private Long sessionId;

    private Long userId;

    private String question;

    /** 问答模式，"ask" 或 "code"。 */
    private String mode;

    /** 答案预览（前 500 字）。 */
    private String answerPreview;

    /** agent 实际迭代轮数。 */
    private Integer iterations;

    /** 累计工具调用次数。 */
    private Integer toolCalls;

    /** 运行状态，例如 "DONE"。 */
    private String status;

    /**
     * AI 声称完成（正则解析 answer_preview）：0=否/null=未解析，1=是。
     * 迁移前旧行为 null，对账时视作 false。
     */
    private Integer claimedSuccess;

    /**
     * AI 声称已验证/测试通过（正则解析 answer_preview）：0=否/null=未解析，1=是。
     * 迁移前旧行为 null，对账时视作 false。
     */
    private Integer claimedVerified;

    /** AI 声称的原话截段（最长 500 字）。 */
    private String claimEvidence;

    private LocalDateTime createdAt;

    // ---- Feature K: K方案分支隔离字段 ----

    /** K方案分支 ID（v0/v1/v2/v3）；NULL 表示非分支单轨。 */
    private String branchId;

    /** 分支内变体序号；NULL 表示非分支单轨。 */
    private Integer variantIndex;

    private String permissionMode;
    private Long sourcePlanRunId;
    private Integer toolTurns;
    private Long wallClockMs;
    private Integer promptTokens;
    private Integer completionTokens;

    /** 父 agent_run.id（子代理用，null 表示顶级 run）。 */
    private Long parentRunId;

    /** 子代理在父 run 中的序号（从 0 开始）。 */
    private Integer runIdx;
}
