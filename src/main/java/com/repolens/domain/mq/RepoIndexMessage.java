package com.repolens.domain.mq;

import com.repolens.domain.enums.TaskType;
import lombok.Data;

@Data
public class RepoIndexMessage {

    private Long repoId;

    private Long taskId;

    private Long userId;

    private TaskType taskType;

    private String idempotentKey;

    private String traceId;
}
