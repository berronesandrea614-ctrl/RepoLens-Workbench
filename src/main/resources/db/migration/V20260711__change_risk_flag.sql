CREATE TABLE IF NOT EXISTS change_risk_flag (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    change_id       BIGINT       NOT NULL COMMENT 'file_change_log.id',
    repo_id         BIGINT       NOT NULL,
    category        VARCHAR(24)  NOT NULL COMMENT 'DESTRUCTIVE/TEST_WEAKENED/SECURITY/SCOPE',
    rule_code       VARCHAR(32)  NOT NULL COMMENT 'DELETE_FILE/DROP_TABLE_DB/TRUNCATE/DELETE_NO_WHERE/RM_RF/MIGRATION_TOUCH/MASS_SHRINK',
    severity        VARCHAR(8)   NOT NULL COMMENT 'BLOCK/WARN',
    reversibility   VARCHAR(16)  NOT NULL DEFAULT 'REVERSIBLE' COMMENT 'IRREVERSIBLE/REVERSIBLE',
    evidence        VARCHAR(512) NULL COMMENT '命中的行/依据',
    acknowledged    TINYINT(1)   NOT NULL DEFAULT 0,
    acknowledged_by BIGINT       NULL,
    acknowledged_at DATETIME     NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_crf_change (change_id),
    KEY idx_crf_repo (repo_id),
    KEY idx_crf_sev (severity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
