package com.repolens.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("shadow_workspace")
public class ShadowWorkspaceEntity {

    public static final String STATUS_ACTIVE    = "ACTIVE";
    public static final String STATUS_MERGED    = "MERGED";
    public static final String STATUS_DISCARDED = "DISCARDED";

    public static final String STRATEGY_WORKTREE  = "WORKTREE";
    public static final String STRATEGY_CLONE_COW = "CLONE_COW";
    public static final String STRATEGY_COPY      = "COPY";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long repoId;
    private Long sessionId;
    private String rootPath;
    private String strategy;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
