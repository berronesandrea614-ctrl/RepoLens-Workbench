package com.repolens.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 编码模式下 agent 覆写文件的变更记录。
 * 每次 writeFileContent 落一行（old/new 全文快照），供前端展示改动与一键 revert。
 */
@Data
@TableName("file_change_log")
public class FileChangeLogEntity {

    /** 已暂存、待人工审批，尚未写盘。 */
    public static final String STATUS_PROPOSED = "PROPOSED";
    /** 已写盘（审批通过或历史直写）。 */
    public static final String STATUS_APPLIED = "APPLIED";
    /** 已拒绝，未写盘。 */
    public static final String STATUS_REJECTED = "REJECTED";
    /** 已回滚（旧内容写回）。 */
    public static final String STATUS_REVERTED = "REVERTED";

    /** 操作类型：覆盖写（writeFileContent / editFileContent）。 */
    public static final String OP_TYPE_WRITE = "WRITE";
    /** 操作类型：新建文件（createFileContent）。 */
    public static final String OP_TYPE_CREATE = "CREATE";
    /** 操作类型：删除文件（deleteFile）。 */
    public static final String OP_TYPE_DELETE = "DELETE";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long repoId;

    private Long sessionId;

    private String filePath;

    private String oldContent;

    private String newContent;

    private LocalDateTime createdAt;

    /** 0=未回滚，1=已回滚。保留以兼容旧数据；REVERTED 状态优先于它。 */
    private Integer reverted;

    /**
     * 变更生命周期状态：PROPOSED（已暂存、待人工审批，未落盘）/ APPLIED（已写盘）/
     * REJECTED（已拒绝、未落盘）/ REVERTED（已回滚）。默认 APPLIED 兼容历史行。
     */
    private String status;

    /**
     * 操作类型：WRITE（writeFileContent/editFileContent 覆盖写）/ CREATE（createFileContent 新建）。
     * 旧行为 NULL，applyOne 降级走 oldContent 空启发。
     */
    private String opType;

    // ---- Feature A: 理解债务复核字段 ----

    /** 人工复核时间戳；NULL 表示未复核。 */
    private java.time.LocalDateTime reviewedAt;

    /**
     * 复核类型：
     * DIFF_VIEWED  = 人工查看过 diff（有效复核 level2）；
     * ACCEPTED     = 仅点了接受按钮（level1）；
     * QUIZZED      = 通过理解测验（level3，quiz_score≥65）。
     * NULL 表示从未复核（level0）。
     */
    private String reviewType;

    /** 在 diff 视图停留的毫秒数；用于防止"秒点"。 */
    private Long dwellMs;

    /** 理解测验得分（0–100）；仅 QUIZZED 时有值。 */
    private Integer quizScore;

    // ---- Feature F: AI 贡献溯源审计字段 ----

    /** 触发本次变更的 LLM 调用 id（llm_call_log.id）；历史行为 NULL，显示「未知(历史变更)」。 */
    private Long llmCallId;

    /** 触发本次变更的 agent 运行 id（agent_run.id）；历史行为 NULL。 */
    private Long agentRunId;

    /** 审批/拒绝/回滚操作人的 user_id；apply/reject/revert 时写入。 */
    private Long approvedBy;

    /** 审批/拒绝/回滚操作时间戳。 */
    private java.time.LocalDateTime approvedAt;

    // ---- Feature K: K方案分支隔离字段 ----

    /** K方案分支 ID（v0/v1/v2/v3）；NULL 表示非分支单轨。 */
    private String branchId;

    /** 分支内变体序号；NULL 表示非分支单轨。 */
    private Integer variantIndex;

    // ---- 自研 AI 内核 §1: 影子工作区 + 验证状态 ----

    /** 1=已即时写入影子区；0=仅落DB（影子写失败降级）。 */
    private Integer writtenToShadow;

    /** 本条变更关联的验证状态：null/PENDING/PASSED/FAILED。 */
    private String verifyStatus;

    /** 本条变更关联的 verification_run.id；null 表示未验证。 */
    private Long verifyRunId;

    private String applyStrategy;
    private String riskLevel;
    private Integer autoApplied;
}
