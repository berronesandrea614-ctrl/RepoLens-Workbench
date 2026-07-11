package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 哈希链完整性校验结果 VO（Feature F P1）。
 * GET /api/repos/{repoId}/provenance/verify
 */
@Data
@Builder
public class ProvenanceVerifyVO {

    /** 整链校验结果：true = 无篡改；false = 检测到篡改。 */
    private boolean verified;

    /**
     * 发生链断裂的 seq（从 1 开始）；verified=true 时为 null。
     * 含义：第 brokenAtSeq 条记录的 record_hash 与重算值不符（可能被改动或删除前一条）。
     */
    private Long brokenAtSeq;

    /** 校验时 repo 的账本记录总数。 */
    private long totalRecords;

    /** 提示信息（降级/边界说明）。 */
    private String note;
}
