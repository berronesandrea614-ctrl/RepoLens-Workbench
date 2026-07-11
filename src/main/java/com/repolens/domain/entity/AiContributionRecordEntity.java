package com.repolens.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 贡献溯源账本记录（Feature F）。
 *
 * 每一行对应一个 apply/reject/revert 决策，形成哈希链（prev_hash + record_hash）。
 * 哈希链防止已入账记录被静默改删；可通过 GET /verify 重算链并比对检测篡改。
 */
@Data
@TableName("ai_contribution_record")
public class AiContributionRecordEntity {

    /** APPROVED = 用户点击应用；变更已写盘。 */
    public static final String DECISION_APPROVED = "APPROVED";
    /** REJECTED = 用户点击拒绝；变更未写盘。 */
    public static final String DECISION_REJECTED = "REJECTED";
    /** REVERTED = 用户回滚已应用的变更；旧内容已恢复。 */
    public static final String DECISION_REVERTED = "REVERTED";

    /** Genesis 块前一哈希（64 个 0，表示链的起点）。 */
    public static final String GENESIS_HASH =
            "0000000000000000000000000000000000000000000000000000000000000000";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long repoId;

    /** 单调递增序号（per repo），哈希链顺序。 */
    private Long seq;

    /** 关联的 file_change_log.id。 */
    private Long changeId;

    /** 触发本次变更的 LLM 调用 id（llm_call_log.id）；历史行为 NULL。 */
    private Long llmCallId;

    /** 触发本次变更的 agent 运行 id（agent_run.id）；历史行为 NULL。 */
    private Long agentRunId;

    /** LLM provider（deepseek/ollama/openai 等）。 */
    private String provider;

    private String modelName;

    private String modelVersion;

    /** prompt SHA-256 哈希（来自 llm_call_log.prompt_hash）。 */
    private String promptHash;

    /** 检索上下文 SHA-256 哈希。 */
    private String contextHash;

    /** prompt 明文快照；仅 app_setting audit.capture-plaintext=true 时写入。 */
    private String promptSnapshot;

    private String filePath;

    /** SHA-256(oldContent + "\n" + newContent)；用于内容完整性校验。 */
    private String diffHash;

    /** 决策：APPROVED / REJECTED / REVERTED。 */
    private String decision;

    /** 批准/拒绝/回滚操作人的 user_id。 */
    private Long approverId;

    /** 操作时间戳。 */
    private LocalDateTime decidedAt;

    /** 前一条记录的 record_hash（genesis 记录使用 GENESIS_HASH）。 */
    private String prevHash;

    /**
     * 本条记录的哈希 = SHA-256(prevHash | repoId | seq | changeId | decision | approverId | decidedAt)。
     * 用于哈希链完整性校验。
     */
    private String recordHash;
}
