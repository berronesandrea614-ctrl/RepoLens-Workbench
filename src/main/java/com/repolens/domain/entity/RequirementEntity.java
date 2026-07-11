package com.repolens.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 需求条目。每条对应某 (user, repo) 下由会话沉淀出的一次需求总结。
 */
@Data
@TableName("requirement")
public class RequirementEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long repoId;

    private Long sessionId;

    /** 关联的 agent_run.id（仅 code 模式 agent 路径有值；纯 RAG 路径为 null）。 */
    private Long agentRunId;

    private String title;

    private String summary;

    /**
     * AI 整体思路一句话：有结构化计划时来自 AgentPlanner.StructuredPlan.approach，
     * 无计划时由 RequirementExtractor 从问答中抽取。
     */
    private String approach;

    /** 需求状态，例如 "SUMMARIZED"。 */
    private String status;

    /**
     * 来源标识：
     * <ul>
     *   <li>{@code "code"}     — 由 AI 会话代码模式 fileChanges 归纳（默认值）</li>
     *   <li>{@code "external"} — 由 Claude Code 文件监听外部改动归纳</li>
     * </ul>
     */
    private String source;

    private LocalDateTime createdAt;

    /**
     * Last update time — set on external-changes merge (B1 fix).
     * Null for freshly inserted requirements that have never been merged.
     * When checking the merge window, fall back to createdAt when updatedAt is null.
     */
    private LocalDateTime updatedAt;
}
