package com.repolens.kernel.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 快照内的一条文件指纹（表 {@code rk_graph_snapshot_file}）。
 *
 * <p>{@code contentHash} 判文件内容是否变过；{@code lastCommitId} 当漂移的 commit 归因锚点。
 */
@Data
@TableName("rk_graph_snapshot_file")
public class RkGraphSnapshotFileEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long snapshotId;

    private Long repoId;

    private String filePath;

    private String contentHash;

    private String lastCommitId;

    private Integer lineCount;
}
