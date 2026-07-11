package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class VectorSearchHitVO {

    private String chunkId;
    private Float score;
    private Long repoId;
    private String filePath;
    private String chunkType;
    private Integer startLine;
    private Integer endLine;
}
