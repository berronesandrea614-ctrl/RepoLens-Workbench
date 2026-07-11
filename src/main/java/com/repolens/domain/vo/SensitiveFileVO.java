package com.repolens.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Sensitive file API output VO. signals are parsed from signalJson.
 */
@Data
public class SensitiveFileVO {

    private Long id;
    private Long repoId;
    private String filePath;

    private Integer fanIn;
    private Integer churn;
    private Double aiRatio;

    /** 1 if matches a PATH_FORBIDDEN rule, exposed as boolean */
    private Boolean constraintHit;

    private Integer finalScore;
    private String severity;
    private String reason;

    /** Parsed signal breakdown from signalJson */
    private Map<String, Object> signals;

    private Integer rankNo;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
