package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 贡献溯源账本记录 VO（Feature F）。
 * 时间线表格：时间 | 文件 | 操作 | 模型 | 批准人 | 决策 | prompt指纹
 */
@Data
@Builder
public class ProvenanceRecordVO {

    private Long id;

    private Long repoId;

    private Long seq;

    private Long changeId;

    /** LLM 调用 id；null 时前端显示「未知(历史变更)」。 */
    private Long llmCallId;

    private Long agentRunId;

    private String provider;

    private String modelName;

    private String modelVersion;

    /**
     * prompt SHA-256 指纹（前8位缩写用于表格显示）。
     * null 时前端显示「未知(历史变更)」。
     */
    private String promptHash;

    private String contextHash;

    private String filePath;

    private String diffHash;

    /** APPROVED / REJECTED / REVERTED */
    private String decision;

    private Long approverId;

    private LocalDateTime decidedAt;

    private String prevHash;

    private String recordHash;

    // ── EU AI Act 合规条款标注（P2，导出时附注） ──

    /**
     * 条款映射摘要（供导出和 UI 脚注）：
     * - 自动记录 → Art.12 / A.6.2.8
     * - ≥6月留存 → Art.19
     * - model version → AI-BOM
     * - approver → Art.14 (人类监督)
     * - record_hash 链 → 可追溯
     */
    private String complianceNote;
}
