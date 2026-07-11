package com.repolens.domain.vo;

import com.repolens.domain.enums.TaskStatus;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ImportRepoResultVO {

    private Long repoId;
    private Long taskId;
    private String latestCommitId;
    private Integer scannedFileCount;
    private Integer savedFileCount;
    private Integer skippedFileCount;
    private TaskStatus status;
    private String errorMsg;
}
