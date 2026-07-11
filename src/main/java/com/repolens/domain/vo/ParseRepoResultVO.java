package com.repolens.domain.vo;

import com.repolens.domain.enums.TaskStatus;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ParseRepoResultVO {

    private Long repoId;
    private Integer parsedFileCount;
    private Integer failedFileCount;
    private Integer classCount;
    private Integer methodCount;
    private Integer apiCount;
    private Integer dependencyCount;
    private TaskStatus status;
    private String errorMsg;
}
