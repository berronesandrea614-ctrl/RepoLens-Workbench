package com.repolens.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 编码模式回答里附带的一条文件变更摘要（id == changeId == file_change_log 主键）。
 * dechainedReqIds: apply/revert 时受影响（STALE/BROKEN）的需求 ID 列表，前端可据此刷新追溯视图。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileChangeVO {
    private Long id;
    private String filePath;
    private Long changeId;
    /** 本次 apply 导致 trace link 变为 STALE/BROKEN 的需求 ID 列表（空表示无影响）。 */
    @Builder.Default
    private List<Long> dechainedReqIds = List.of();
}
