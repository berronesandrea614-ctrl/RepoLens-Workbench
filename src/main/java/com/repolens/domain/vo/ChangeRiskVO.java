package com.repolens.domain.vo;

import lombok.Data;

/**
 * Change risk flag API output VO.
 *
 * <p>Represents a detected risky operation in a file change, exposed to clients
 * with acknowledged as boolean instead of integer.
 */
@Data
public class ChangeRiskVO {

    private Long changeId;

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

    /** Acknowledged flag as boolean */
    private Boolean acknowledged;
}
