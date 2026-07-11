-- K方案分支图 P1 迁移
-- 用户手动 apply，Flyway 版本: V20260712

ALTER TABLE file_change_log ADD COLUMN branch_id VARCHAR(16) NULL COMMENT 'K方案分支隔离 v0/v1.. NULL=非分支单轨';
ALTER TABLE file_change_log ADD COLUMN variant_index INT NULL;
ALTER TABLE agent_run ADD COLUMN branch_id VARCHAR(16) NULL;
ALTER TABLE agent_run ADD COLUMN variant_index INT NULL;

CREATE TABLE IF NOT EXISTS solution_branch (
    id                BIGINT PRIMARY KEY AUTO_INCREMENT,
    repo_id           BIGINT       NOT NULL,
    session_id        BIGINT       NOT NULL,
    parent_branch_id  BIGINT       NULL COMMENT 'CYOA 树,P1 都为 NULL(平铺)',
    agent_run_id      BIGINT       NULL,
    branch_id         VARCHAR(16)  NOT NULL COMMENT 'v0/v1/v2/v3',
    variant_index     INT          NOT NULL,
    label             VARCHAR(128) NULL,
    approach          VARCHAR(512) NULL,
    strategy_hint     VARCHAR(128) NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'GENERATING' COMMENT 'GENERATING/READY/SELECTED/DISCARDED',
    files_changed     INT          NOT NULL DEFAULT 0,
    blast_radius_size INT          NOT NULL DEFAULT 0,
    debt_delta        INT          NOT NULL DEFAULT 0,
    confidence        DOUBLE       NULL,
    verified          TINYINT(1)   NOT NULL DEFAULT 0 COMMENT 'P1恒0未落盘无法真测',
    degraded          TINYINT(1)   NOT NULL DEFAULT 1 COMMENT 'P1 confidence只静态自评',
    question          VARCHAR(1024) NULL,
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_sb_repo_session (repo_id, session_id),
    KEY idx_sb_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
