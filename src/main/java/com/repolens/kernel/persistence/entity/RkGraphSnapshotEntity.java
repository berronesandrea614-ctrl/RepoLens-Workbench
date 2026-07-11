package com.repolens.kernel.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次调用图快照（表 {@code rk_graph_snapshot}）——M9 给无历史的调用图补上的时间维度。
 *
 * <p>{@code graphHash} 是整张图的确定性 sha256（排序后节点 key + 边 key），当「时间指纹」；
 * {@code prevHash} 指向前一快照的 graphHash，串成防篡改审计链；{@code seq} 是本 repo 内序号，回放演化按它排。
 */
@Data
@TableName("rk_graph_snapshot")
public class RkGraphSnapshotEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long repoId;

    /** 哪个会话抓的（漂移归因锚点之一）。 */
    private Long sessionId;

    /** 本 repo 内快照序号（1 起）。 */
    private Integer seq;

    private String label;

    /** 代表性 commit（各文件 last_commit_id 唯一时取之，否则空）。 */
    private String commitRef;

    private Integer nodeCount;

    private Integer edgeCount;

    private Integer fileCount;

    /** 整张图确定性 sha256，当时间指纹。 */
    private String graphHash;

    /** 前一快照的 graphHash（审计链）。 */
    private String prevHash;

    private LocalDateTime capturedAt;
}
