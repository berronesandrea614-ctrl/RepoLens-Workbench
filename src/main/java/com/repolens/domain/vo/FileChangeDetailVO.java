package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文件变更明细（含 old/new 全文），供变更查看与 diff / revert 使用。
 */
@Data
@Builder
public class FileChangeDetailVO {
    private Long id;
    private String filePath;
    private String oldContent;
    private String newContent;
    private LocalDateTime createdAt;
    private Integer reverted;
    /** 变更状态：PROPOSED / APPLIED / REJECTED / REVERTED。 */
    private String status;
}
