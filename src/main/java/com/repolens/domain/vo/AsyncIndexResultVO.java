package com.repolens.domain.vo;

import com.repolens.domain.enums.TaskStatus;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AsyncIndexResultVO {

    private Long repoId;

    private Long firstTaskId;

    private TaskStatus status;

    private String traceId;

    private String message;

    private Boolean lockAcquired;

    private Boolean lockDegraded;
}
