package com.repolens.domain.vo;

import com.repolens.domain.enums.TaskStatus;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BuildChunkResultVO {

    private Long repoId;
    private Integer totalFileCount;
    private Integer javaFileCount;
    private Integer configFileCount;
    private Integer classChunkCount;
    private Integer methodChunkCount;
    private Integer apiChunkCount;
    private Integer configChunkCount;
    private Integer docChunkCount;
    private Integer totalChunkCount;
    private Integer failedFileCount;
    private TaskStatus status;
    private String errorMsg;
}
