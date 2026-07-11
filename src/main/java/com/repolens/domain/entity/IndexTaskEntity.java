package com.repolens.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.repolens.domain.enums.TaskStatus;
import com.repolens.domain.enums.TaskType;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("index_task")
public class IndexTaskEntity extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long repoId;

    private TaskType taskType;

    private TaskStatus status;

    private Integer retryCount;

    private Integer maxRetry;

    private String idempotentKey;

    private String errorMsg;
}
