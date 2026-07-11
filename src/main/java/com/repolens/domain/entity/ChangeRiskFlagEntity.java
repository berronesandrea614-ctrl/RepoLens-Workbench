package com.repolens.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Change risk flag entity for P1 destructive operation detection.
 *
 * <p>Represents detected risky operations in file changes, categorized by
 * risk type (DESTRUCTIVE, TEST_WEAKENED, SECURITY, SCOPE) with severity
 * level and reversibility assessment.
 */
@Data
@TableName("change_risk_flag")
public class ChangeRiskFlagEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** File change log ID reference */
    private Long changeId;

    /** Repository ID */
    private Long repoId;

    /** Risk category: DESTRUCTIVE/TEST_WEAKENED/SECURITY/SCOPE */
    private String category;

    /** Rule code: DELETE_FILE/DROP_TABLE_DB/TRUNCATE/DELETE_NO_WHERE/RM_RF/MIGRATION_TOUCH/MASS_SHRINK */
    private String ruleCode;

    /** Severity: BLOCK/WARN */
    private String severity;

    /** Reversibility: IRREVERSIBLE/REVERSIBLE */
    private String reversibility;

    /** Evidence: matched line or context */
    private String evidence;

    /** 0/1 acknowledged flag */
    private Integer acknowledged;

    /** User ID who acknowledged */
    private Long acknowledgedBy;

    /** Timestamp when acknowledged */
    private LocalDateTime acknowledgedAt;

    /** Creation timestamp */
    private LocalDateTime createdAt;
}
