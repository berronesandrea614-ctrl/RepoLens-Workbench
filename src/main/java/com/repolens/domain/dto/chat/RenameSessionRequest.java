package com.repolens.domain.dto.chat;

import lombok.Data;

/**
 * 重命名会话请求体：{ title }。
 */
@Data
public class RenameSessionRequest {

    private String title;
}
