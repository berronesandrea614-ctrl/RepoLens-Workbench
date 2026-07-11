package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 会话内单条消息：角色、内容与解析后的代码引用（referencesJson 解析失败则为空列表）。
 */
@Data
@Builder
public class ChatMessageVO {

    private Long id;
    /** "USER" / "ASSISTANT"。 */
    private String role;
    private String content;
    private List<CodeReferenceVO> references;
}
