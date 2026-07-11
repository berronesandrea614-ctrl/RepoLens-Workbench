package com.repolens.kernel.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 两快照间检出的一处架构漂移（表 {@code rk_arch_drift}）——M9 的结果表，前端时间轴读它。
 *
 * <p>{@code driftType}：NODE_ADDED/NODE_REMOVED/EDGE_ADDED/EDGE_REMOVED/FILE_ADDED/FILE_REMOVED/FILE_CHANGED。
 * {@code attributedSessionId}/{@code attributedCommit}：把这处漂移归因到引入它的会话/commit（to 快照的上下文）。
 */
@Data
@TableName("rk_arch_drift")
public class RkArchDriftEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long repoId;

    private Long fromSnapshotId;

    private Long toSnapshotId;

    private String driftType;

    private String entityKeyHash;

    /** 人读的漂移主体（如 com.demo.Calc#add(int,int) 或文件路径）。 */
    private String entityDesc;

    private String filePath;

    private String language;

    private Long attributedSessionId;

    private String attributedCommit;

    private LocalDateTime detectedAt;
}
