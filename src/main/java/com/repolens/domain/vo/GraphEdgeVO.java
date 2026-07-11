package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GraphEdgeVO {
    private String id;
    private String source;
    private String target;
    private String relationType;
    private double confidence;
    private String dataType;
}
