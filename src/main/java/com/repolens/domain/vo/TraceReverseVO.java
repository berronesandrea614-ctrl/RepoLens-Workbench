package com.repolens.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Feature C P1: 反向追溯 VO — 符号 → 哪些需求实现它。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TraceReverseVO {

    private Long symbolId;
    private String symbolName;
    private String layer;
    private List<ReqLink> requirements;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReqLink {
        private Long requirementId;
        private String title;
        private String linkType;
        private double confidence;
        private String status;
    }
}
