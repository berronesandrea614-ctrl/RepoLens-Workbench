-- B-P2 Migration: repo_constraint_rule table
-- MySQL 8 plain CREATE (no IF NOT EXISTS per project convention)
-- **Live DB: apply this script manually before deploying B-P2.**

CREATE TABLE repo_constraint_rule (
    id          BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    repo_id     BIGINT        NOT NULL,
    source_hash VARCHAR(64)   NOT NULL COMMENT 'SHA-256 of AGENTS.md content',
    rule_type   VARCHAR(30)   NOT NULL COMMENT 'PATH_FORBIDDEN|FILETYPE_FORBIDDEN|NO_NEW_DEP|MUST_VERIFY|KEEP_SCOPE|SEMANTIC',
    pattern     VARCHAR(500)  DEFAULT NULL COMMENT 'glob/regex pattern extracted from rule sentence',
    raw_text    VARCHAR(1000) NOT NULL COMMENT 'original rule sentence',
    checkable   TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '0=advisory only (SEMANTIC), 1=checkable',
    severity    VARCHAR(10)   NOT NULL DEFAULT 'BLOCK' COMMENT 'BLOCK|WARN',
    created_at  DATETIME      NOT NULL,
    INDEX idx_rcr_repo_hash (repo_id, source_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Parsed constraint rules cache, keyed by repo+source_hash';
