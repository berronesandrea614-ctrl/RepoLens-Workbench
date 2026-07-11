package com.repolens.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Cached parsed constraint rule (repo_constraint_rule table).
 * Rules are keyed by (repo_id, source_hash) — SHA-256 of the AGENTS.md content.
 * When AGENTS.md changes (source_hash changes), old rules for the repo are deleted
 * and new ones are inserted.
 */
@Data
@TableName("repo_constraint_rule")
public class RepoConstraintRuleEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long repoId;

    /** SHA-256 hex of the AGENTS.md / rules file content used to produce this rule. */
    private String sourceHash;

    /** Rule type: PATH_FORBIDDEN / FILETYPE_FORBIDDEN / NO_NEW_DEP / MUST_VERIFY / KEEP_SCOPE / SEMANTIC */
    private String ruleType;

    /** Glob / path pattern; null for typeless rules. */
    private String pattern;

    /** Original rule sentence from the rules file. */
    private String rawText;

    /** 1 = checkable (may produce violations); 0 = advisory only (SEMANTIC). */
    private Boolean checkable;

    /** BLOCK or WARN. */
    private String severity;

    private LocalDateTime createdAt;
}
