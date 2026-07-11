package com.repolens.kernel.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 单次文件改动记录（表 {@code rk_file_change}）。
 *
 * <p>避坑：旧版 {@code file_change_log} 用 MEDIUMTEXT 内联改动全文，大文件撑爆 DB。
 * 本版只存 {@code oldHash}/{@code newHash} + {@code diffRef}（diff/全文落磁盘的外部引用），
 * DB 里不留全文。{@code oldHash} 供"读后编不变式"校验（编辑前磁盘内容须与 agent 所读一致）。
 */
@Data
@TableName("rk_file_change")
public class RkFileChangeEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long repoId;

    private Long sessionId;

    private Long runId;

    /** 落到哪个影子区。 */
    private Long shadowId;

    private String filePath;

    /** 操作类型：WRITE / CREATE / DELETE / RENAME。 */
    private String opType;

    /** 旧内容 sha256（读后编不变式校验用）；CREATE 时为 null。 */
    private String oldHash;

    /** 新内容 sha256；DELETE 时为 null。 */
    private String newHash;

    /** diff/全文外部存储引用，非内联全文。 */
    private String diffRef;

    /** 状态机：PROPOSED / WRITTEN_TO_SHADOW / MERGED / REVERTED / DISCARDED。 */
    private String status;

    private LocalDateTime createdAt;
}
