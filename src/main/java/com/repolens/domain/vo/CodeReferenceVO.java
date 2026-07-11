package com.repolens.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeReferenceVO {

    private String filePath;
    private String chunkType;
    private String className;
    private String methodName;
    private Integer startLine;
    private Integer endLine;
    private Float score;
    private String contentPreview;
}
