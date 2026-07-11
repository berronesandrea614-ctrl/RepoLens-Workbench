package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class AgentMemoryVO {

    private Long id;
    private String content;
    private String keywords;
    private LocalDateTime createdAt;
    /** 记忆类型，用于前端展示标签：FACT / PREFERENCE / DECISION / CONSTRAINT。 */
    private String memoryType;
    /** 重要程度 1-5，越高越重要。 */
    private Integer importance;
}
