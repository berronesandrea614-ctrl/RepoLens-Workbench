package com.repolens.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Feature C: 双向可追溯地图 VO — 度量指标 + 二部图节点/边。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TraceMapVO {

    private Metrics metrics;
    private List<TraceNode> nodes;
    private List<TraceEdge> edges;
    /** true = 向量/LLM 不可用，仅 DECLARED links，结果可能低估。 */
    private boolean degraded;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Metrics {
        private double coverage;
        private int orphanCount;
        private int danglingCount;
        private int staleCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TraceNode {
        /** "req" or "sym" */
        private String nodeType;
        private String id;
        private String label;
        private String layer;
        /** "dangling" for req with no link; "orphan" for symbol with no link */
        private String flag;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TraceEdge {
        private String source;
        private String target;
        private String linkType;
        private double confidence;
        /** "stale" = STALE/BROKEN link → red-dashed in UI */
        private String status;
    }
}
