package com.repolens.domain.vo;

import com.repolens.domain.enums.TaskStatus;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class VectorizeResultVO {

    private Long repoId;
    private Integer pendingChunkCount;
    private Integer embeddedChunkCount;
    private Integer failedChunkCount;
    private TaskStatus status;
    private String errorMsg;
}
