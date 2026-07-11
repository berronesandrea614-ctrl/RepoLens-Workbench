package com.repolens.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chat_message")
public class ChatMessageEntity extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    private String role;

    private String content;

    private String referencesJson;
}
