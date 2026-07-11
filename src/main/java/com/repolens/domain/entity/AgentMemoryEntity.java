package com.repolens.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Agent 长期记忆条目。每条对应某 (user, repo) 下沉淀的一条可复用笔记。
 */
@Data
@TableName("agent_memory")
public class AgentMemoryEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long repoId;

    private String content;

    private String keywords;

    private Long sourceSessionId;

    private LocalDateTime createdAt;

    /** 记忆类型：FACT / PREFERENCE / DECISION / CONSTRAINT。默认 FACT。 */
    private String memoryType;

    /** 重要程度 1-5，越高越重要。默认 3。 */
    private Byte importance;

    /** 置信度 0.00-1.00。默认 0.80。 */
    private BigDecimal confidence;

    /** 最后被召回的时间（首次未召回时为 null）。 */
    private LocalDateTime lastAccessedAt;

    /** 累计被召回次数。 */
    private Integer accessCount;
}
