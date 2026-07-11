package com.repolens.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 可追溯度快照（惰性重算，每个 repo 最多一行）。
 * Feature C: coverage/orphan_count/dangling_count/stale_count/detail_json。
 */
@Data
@TableName("trace_snapshot")
public class TraceSnapshotEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long repoId;

    /** 覆盖率：有 LINKED link 的需求数 / 总需求数。 */
    private Double coverage;

    /** 孤儿符号数：核心层符号无 LINKED link。 */
    private Integer orphanCount;

    /** 悬空需求数：需求无 LINKED link。 */
    private Integer danglingCount;

    /** 脱钩链接数：status=STALE 或 BROKEN 的 requirement_symbol 行数。 */
    private Integer staleCount;

    /** 二部图节点/边 JSON（前端直接反序列化）。 */
    private String detailJson;

    /** 向量/LLM 不可用时置 1（仅 DECLARED links）。 */
    private Integer degraded;

    private LocalDateTime computedAt;
}
