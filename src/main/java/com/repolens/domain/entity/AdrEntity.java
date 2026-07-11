package com.repolens.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ADR (Architecture Decision Record) entity.
 *
 * <p>Tracks architecture decisions made within a repository, including context,
 * decision rationale, consequences, and decision status.
 */
@Data
@TableName("adr")
public class AdrEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** Repository ID */
    private Long repoId;

    /** User ID (creator or owner) */
    private Long userId;

    /** Per-repo sequential ADR number */
    private Integer number;

    /** ADR title */
    private String title;

    /** ADR status: PROPOSED / ACCEPTED / SUPERSEDED */
    private String status;

    /** Context section: background and problem statement */
    private String context;

    /** Decision section: what was decided */
    private String decision;

    /** Consequences section: implications and tradeoffs */
    private String consequences;

    /** JSON array of decision drivers */
    private String driversJson;

    /** JSON array of considered options */
    private String optionsJson;

    /** Source type: REQUIREMENT / DECISION_MEMORY / MANUAL */
    private String sourceType;

    /** Source ID (requirement id / memory id) */
    private Long sourceId;

    /** File path: docs/adr/NNNN.md once accepted */
    private String filePath;

    /** Superseded by (ID of superseding ADR) */
    private Long supersededBy;

    /** Degraded flag (0 = not degraded, 1 = degraded) */
    private Integer degraded;

    /** Creation timestamp */
    private LocalDateTime createdAt;

    /** Update timestamp */
    private LocalDateTime updatedAt;
}
