package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class BlastSubgraphVO {
    private List<GraphNodeVO> nodes;
    private List<GraphEdgeVO> edges;
}
