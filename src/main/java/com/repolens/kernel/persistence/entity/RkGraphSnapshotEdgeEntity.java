package com.repolens.kernel.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 快照内的一条依赖边（表 {@code rk_graph_snapshot_edge}）。
 *
 * <p>{@code keyHash} = sha256(源稳定 key -&gt; 目标名 : 关系)——跨快照识别「同一条边」的依据。
 * {@code sourceKeyHash} 是源符号节点的 keyHash（便于回连节点）。
 */
@Data
@TableName("rk_graph_snapshot_edge")
public class RkGraphSnapshotEdgeEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long snapshotId;

    private Long repoId;

    /** sha256(sourceStableKey -> targetName : relationType)。 */
    private String keyHash;

    /** 源符号节点的 keyHash。 */
    private String sourceKeyHash;

    private String targetName;

    private String relationType;

    private Double confidence;
}
