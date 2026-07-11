package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CodeGraphVO {
    private String rootId;
    private List<GraphNodeVO> nodes;
    private List<GraphEdgeVO> edges;
    private int nodeCount;
    private int edgeCount;
    private boolean truncated;
}
