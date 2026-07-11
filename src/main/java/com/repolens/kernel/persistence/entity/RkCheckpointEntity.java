package com.repolens.kernel.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话 Checkpoint（表 {@code rk_checkpoint}，M5.3）：给「影子区代码 + 对话步序」打一个可回滚的点。
 *
 * <p>避坑：不内联影子区全文——{@code shadowSnapshotRef} 只记快照目录（快照文件落影子区
 * {@code .rk/checkpoints/<id>/}），{@code transcriptJson} 存打点时的对话，{@code stepIndex}
 * 记打点时的对话步数。rewind 时按此把影子区代码还原、把对话截断回该点。
 */
@Data
@TableName("rk_checkpoint")
public class RkCheckpointEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long repoId;

    private Long sessionId;

    private Long runId;

    /** 打点时的活跃影子区。 */
    private Long shadowId;

    private String label;

    /** 影子区快照目录（相对影子根），rewind 据此还原代码。 */
    private String shadowSnapshotRef;

    /** 打点时的对话 JSON（可空/可截断）。 */
    private String transcriptJson;

    /** 打点时的对话步序（rewind 把对话截断回此长度）。 */
    private Integer stepIndex;

    private LocalDateTime createdAt;
}
