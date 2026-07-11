package com.repolens.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("checkpoint")
public class CheckpointEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    private Long parentId;

    private Long highWaterMark;

    private Long lastMessageId;

    private String label;

    private LocalDateTime createdAt;
}
