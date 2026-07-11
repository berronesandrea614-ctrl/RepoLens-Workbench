package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FileContentVO {

    private Long repoId;
    private String filePath;
    private Integer startLine;
    private Integer endLine;
    private String content;
}
