package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class RequirementVO {

    private Long id;
    private String title;
    private String summary;
    private String status;
    /** 来源：code（AI 会话）| external（Claude Code 外部改动）。 */
    private String source;
    /** 该需求关联的去重文件数（distinct requirement_symbol.filePath）。 */
    private int fileCount;
    private LocalDateTime createdAt;
}
