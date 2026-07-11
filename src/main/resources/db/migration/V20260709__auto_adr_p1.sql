CREATE TABLE IF NOT EXISTS sensitive_file (
    id               BIGINT PRIMARY KEY AUTO_INCREMENT,
    repo_id          BIGINT        NOT NULL,
    file_path        VARCHAR(512)  NOT NULL,
    fan_in           INT           NOT NULL DEFAULT 0 COMMENT 'raw dependency in-degree',
    churn            INT           NOT NULL DEFAULT 0 COMMENT 'raw applied/reverted change count',
    ai_ratio         DOUBLE        NOT NULL DEFAULT 0 COMMENT 's1AiRatio [0,1]',
    constraint_hit   TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '1 if matches a PATH_FORBIDDEN rule',
    final_score      INT           NOT NULL DEFAULT 0 COMMENT '0-100 weighted score',
    severity         VARCHAR(8)    NOT NULL DEFAULT 'INFO' COMMENT 'BLOCK/WARN/INFO',
    reason           VARCHAR(512)  NULL COMMENT 'human-readable why-sensitive',
    signal_json      TEXT          NULL COMMENT 'normalized signal breakdown JSON',
    rank_no          INT           NOT NULL DEFAULT 0 COMMENT 'per-repo rank 1..N',
    created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sf_repo_path (repo_id, file_path),
    KEY idx_sf_repo (repo_id),
    KEY idx_sf_severity (severity)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
