package com.repolens.domain.dto.chat;

import com.repolens.domain.enums.PermissionMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class CodeAnswerRequest {

    private Long sessionId;

    @NotBlank
    private String question;

    @Max(20)
    private Integer topK;

    private Boolean useLlm;

    /** 交互模式："ask"（默认，只读问答）或 "code"（编码模式，可用写工具）。null/空白按 ask 处理。 */
    private String mode;

    /** 用户 @提及的上下文，最多 5 条；超出则 400 */
    @Size(max = 5, message = "mentions cannot exceed 5")
    private List<MentionDTO> mentions;

    private PermissionMode permissionMode;

    /**
     * 实时改动流开关（Cursor 式「边写边高亮」）：为真时内核 agent 每把一个文件写进影子区就实时推
     * {@code file_change} SSE 事件（影子区 vs 真目录 diff），供前端编辑器实时高亮。null/false 时不推、行为不变。
     */
    private Boolean realtimeDiff;
}
