package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话列表项：供聊天面板展示历史会话（标题、消息数、最新消息预览、创建时间）。
 */
@Data
@Builder
public class ChatSessionVO {

    private Long id;
    private String title;
    private int messageCount;
    /** 最新一条消息内容的前 ~60 字符；无消息则为空串。 */
    private String lastMessagePreview;
    private LocalDateTime createdAt;
}
