package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class RagSearchResultVO {

    private Long repoId;
    private String query;
    private Integer topK;
    private Integer hitCount;
    private Boolean degraded;
    private String degradeReason;
    private List<RagChunkVO> results;
}
