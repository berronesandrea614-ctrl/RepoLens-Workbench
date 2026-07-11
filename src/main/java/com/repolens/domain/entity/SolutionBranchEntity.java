package com.repolens.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * K方案分支图节点实体。
 *
 * <p>每一个 solution_branch 行代表一次多方案并行分析中的一条候选解，
 * 通过 branch_id(v0/v1/v2/v3) + variant_index 定位。
 * P1 阶段 parent_branch_id 均为 NULL（平铺），CYOA 树形结构留待 P2 扩展。
 */
@Data
@TableName("solution_branch")
public class SolutionBranchEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属仓库 ID。 */
    private Long repoId;

    /** 所属会话 ID。 */
    private Long sessionId;

    /** 父分支 ID（CYOA 树；P1 恒 NULL，平铺）。 */
    private Long parentBranchId;

    /** 触发本分支的 agent_run.id；可为 NULL。 */
    private Long agentRunId;

    /** 分支标识符，如 v0/v1/v2/v3。 */
    private String branchId;

    /** 分支内变体序号（与 branch_id 对应）。 */
    private Integer variantIndex;

    /** 人类可读的分支标签。 */
    private String label;

    /** 本方案的实现思路描述（最长 512 字）。 */
    private String approach;

    /** 策略提示，用于前端渲染角标（最长 128 字）。 */
    private String strategyHint;

    /**
     * 分支生命周期状态：
     * GENERATING = 生成中；READY = 已就绪；SELECTED = 已选中；DISCARDED = 已丢弃。
     */
    private String status;

    /** 本分支影响的文件数。 */
    private Integer filesChanged;

    /** 爆炸半径大小（受影响符号/引用数量）。 */
    private Integer blastRadiusSize;

    /** 技术债变化量（正=增债，负=还债）。 */
    private Integer debtDelta;

    /** 静态自评置信度 [0,1]；P1 仅静态自评，degraded=1。 */
    private Double confidence;

    /** 是否已通过验证；P1 恒 0（未落盘，无法真测）。 */
    private Integer verified;

    /** 是否降级评估；P1 恒 1（confidence 仅静态自评）。 */
    private Integer degraded;

    /** 生成本分支时的原始问题文本。 */
    private String question;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
