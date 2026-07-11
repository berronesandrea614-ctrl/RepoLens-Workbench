package com.repolens.kernel.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 单个方案分支（表 {@code rk_solution_branch}）——M8 多方案对比里的一个候选实现。
 *
 * <p>每个分支独占一个 {@code runId}（= 本行 id，自命名空间避免与真 agent_run 冲突）与一个影子区
 * （{@code shadowId}），并行跑各自的 {@link com.repolens.kernel.loop.AgentLoopExecutor}——互不落盘、互不干扰。
 *
 * <p>指标全部来自真实 staged 改动（{@code rk_file_change} 按 {@code shadowId} 归集后统计），
 * {@code metricKind=REAL} 诚实标注是真跑出来的、不是预估。{@code verified} 在 P1 不物理跑测试时保持
 * {@code null}——宁可空着也不谎报绿灯（对齐内核 failing-until-tested 纪律）。
 */
@Data
@TableName("rk_solution_branch")
public class RkSolutionBranchEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long setId;

    private Long repoId;

    private Long sessionId;

    /** 本分支独占 run（落库后回填为本行 id）。 */
    private Long runId;

    /** 本分支独占影子区。 */
    private Long shadowId;

    /** 方案名。 */
    private String label;

    /** 喂给该分支的架构策略提示（制造多样性）。 */
    private String strategyHint;

    private Integer variantIndex;

    /** REAL（自研真实 staged）/ PREDICTED（Claude 静态预估）。 */
    private String metricKind;

    /** STAGED / SELECTED / DISCARDED / FAILED。 */
    private String status;

    private Integer filesChanged;

    private Integer linesAdded;

    private Integer linesRemoved;

    private Long tokensSpent;

    private Integer turns;

    /** 静态/真跑验证结论；P1 未跑为 null（不谎报绿）。 */
    private Boolean verified;

    private String terminationReason;

    /** 该分支 agent 的收尾说明（截断入库）。 */
    private String finalText;

    private LocalDateTime createdAt;
}
