package com.repolens.kernel.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 快照内的一个符号节点（表 {@code rk_graph_snapshot_node}）。
 *
 * <p>{@code keyHash} = sha256(语义稳定 key)——跨快照识别「同一个符号」的依据（不认会变的自增 id）。
 * 其余字段仅供漂移结果的人读展示。
 */
@Data
@TableName("rk_graph_snapshot_node")
public class RkGraphSnapshotNodeEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long snapshotId;

    private Long repoId;

    /** sha256(language|filePath|symbolType|className|methodName|signature)。 */
    private String keyHash;

    private String language;

    private String symbolType;

    private String className;

    private String methodName;

    private String signature;

    private String filePath;

    private Integer startLine;

    private Integer endLine;
}
