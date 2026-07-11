package com.repolens.kernel.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 影子工作区记录（表 {@code rk_shadow_workspace}）。
 *
 * <p>agent 在此隔离副本里真写盘，验证校验的是"自己的"改动，人只审批"是否合并回真目录"。
 * 相较 DeepSeek 旧版 {@code shadow_workspace}，本版补 {@code run_id}（改动归属清晰）
 * 与 {@code base_commit}（回滚/对拍锚点）。
 */
@Data
@TableName("rk_shadow_workspace")
public class RkShadowWorkspaceEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long repoId;

    private Long sessionId;

    /** 关联的 agent run；null 表示尚未绑定具体 run。 */
    private Long runId;

    /** 影子区根目录绝对路径。 */
    private String rootPath;

    /** 基线 commit，回滚/对拍锚点。 */
    private String baseCommit;

    /** 落盘策略：CLONE_COW / OVERLAY / COPY。 */
    private String strategy;

    /** 生命周期状态：ACTIVE / MERGED / DISCARDED。 */
    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
