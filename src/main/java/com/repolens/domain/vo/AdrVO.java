package com.repolens.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ADR API output VO. drivers/options are parsed from JSON arrays.
 */
@Data
public class AdrVO {

    private Long id;
    private Long repoId;

    /** Per-repo sequential number; null while PROPOSED. */
    private Integer number;

    private String title;
    private String status;
    private String context;
    private String decision;
    private String consequences;

    private List<String> drivers;
    private List<String> options;

    private String sourceType;
    private Long sourceId;
    private String filePath;
    private Long supersededBy;

    /** 0 = not degraded (LLM ok), 1 = degraded (template fallback used). */
    private Integer degraded;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
