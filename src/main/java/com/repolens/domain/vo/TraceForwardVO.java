package com.repolens.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Feature C: 正向追溯 VO — 需求 → 实现符号列表 + 覆盖率。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TraceForwardVO {

    private Long requirementId;
    private String title;
    /** 该需求的 LINKED link 数 / 全部 link 数（0 = 悬空）。 */
    private double coverage;
    private List<TraceLink> links;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TraceLink {
        private Long symbolId;
        private String filePath;
        private Integer startLine;
        private String linkType;
        private double confidence;
        private String status;
        private String symbolName;
        private String layer;
    }
}
