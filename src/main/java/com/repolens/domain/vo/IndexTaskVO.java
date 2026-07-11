package com.repolens.domain.vo;

import com.repolens.domain.enums.TaskStatus;
import com.repolens.domain.enums.TaskType;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class IndexTaskVO {

    private Long id;
    private Long repoId;
    private TaskType taskType;
    private TaskStatus status;
    private Integer retryCount;
    private Integer maxRetry;
    private String idempotentKey;
    private String errorMsg;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
