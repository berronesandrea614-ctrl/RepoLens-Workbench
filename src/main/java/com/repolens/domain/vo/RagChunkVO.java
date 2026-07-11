package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RagChunkVO {

    private String chunkId;
    private String filePath;
    private String chunkType;
    private String language;
    private Integer startLine;
    private Integer endLine;
    private Float score;
    private String content;
    private String contentPreview;
}
