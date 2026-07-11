package com.repolens.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Sensitive file entity for automatic ADR detection.
 *
 * <p>Represents files that may require architecture decision records based on
 * dependency complexity, code churn, and AI-assisted edit ratios.
 */
@Data
@TableName("sensitive_file")
public class SensitiveFileEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** Repository ID */
    private Long repoId;

    /** File path within repository */
    private String filePath;

    /** Raw dependency in-degree */
    private Integer fanIn;

    /** Raw applied/reverted change count */
    private Integer churn;

    /** s1AiRatio [0,1] */
    private Double aiRatio;

    /** 1 if matches a PATH_FORBIDDEN rule */
    private Integer constraintHit;

    /** 0-100 weighted score */
    private Integer finalScore;

    /** Severity: BLOCK/WARN/INFO */
    private String severity;

    /** Human-readable why-sensitive */
    private String reason;

    /** Normalized signal breakdown JSON */
    private String signalJson;

    /** Per-repo rank 1..N */
    private Integer rankNo;

    /** Creation timestamp */
    private LocalDateTime createdAt;

    /** Update timestamp */
    private LocalDateTime updatedAt;
}
