package com.repolens.kernel.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 方案组容器（表 {@code rk_solution_set}）——M8 多方案对比的锚点。
 *
 * <p>一个 set 对应一次「有多种合理实现」的任务分叉：并行跑 N 个分支（{@link RkSolutionBranchEntity}），
 * 各自独立影子区隔离产出真实 staged 改动与真实指标，打分推荐；用户选定后只合并选中分支、其余 DISCARDED。
 *
 * <p>{@code repoDir} 内嵌在 set 里，让 {@code select} 合并回真目录时自证不依赖外部解析（隔壁窗口契约）。
 */
@Data
@TableName("rk_solution_set")
public class RkSolutionSetEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long repoId;

    private Long sessionId;

    /** 触发多方案的原始任务。 */
    private String question;

    /** 真目录根（select 合并锚点）。 */
    private String repoDir;

    /** 引擎：NATIVE（自研并行 staged）/ CLAUDE（静态预估）。 */
    private String engine;

    /** GENERATING / READY / SELECTED / DISCARDED / FAILED。 */
    private String status;

    /** 实际产出的方案分支数。 */
    private Integer variantCount;

    private Long selectedBranchId;

    private Long recommendedBranchId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
