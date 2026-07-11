-- ADR（架构决策记录）表。
-- 说明：本项目 MySQL 的 schema 由 db/init/*.sql 在容器首次初始化时
-- (docker-entrypoint-initdb.d) 一次性建立；db/migration/*.sql 仅用于 H2 测试，
-- 不会作用于 MySQL。ADR 特性(原 V20260708__adr_mvp.sql)当初漏加进 db/init，
-- 导致既有库缺 adr 表，ADR 功能查询报 DATABASE_ERROR（前端「数据库不可用」）。
-- 此处按 04_rewrite_kernel_M1.sql 同款「按特性追加 init 文件」的约定补上。
USE repolens;

CREATE TABLE IF NOT EXISTS adr (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    repo_id       BIGINT        NOT NULL,
    user_id       BIGINT        NOT NULL,
    number        INT           NULL COMMENT 'per-repo sequential ADR number, assigned on accept (NULL while PROPOSED)',
    title         VARCHAR(255)  NOT NULL,
    status        VARCHAR(16)   NOT NULL DEFAULT 'PROPOSED' COMMENT 'PROPOSED/ACCEPTED/SUPERSEDED',
    context       TEXT          NULL,
    decision      TEXT          NULL,
    consequences  TEXT          NULL,
    drivers_json  TEXT          NULL COMMENT 'JSON array of decision drivers',
    options_json  TEXT          NULL COMMENT 'JSON array of considered options',
    source_type   VARCHAR(24)   NOT NULL DEFAULT 'REQUIREMENT' COMMENT 'REQUIREMENT/DECISION_MEMORY/MANUAL',
    source_id     BIGINT        NULL COMMENT 'requirement id / memory id',
    file_path     VARCHAR(512)  NULL COMMENT 'docs/adr/NNNN.md once accepted',
    superseded_by BIGINT        NULL,
    degraded      TINYINT(1)    NOT NULL DEFAULT 0,
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_adr_repo_number (repo_id, number),
    KEY idx_adr_repo (repo_id),
    KEY idx_adr_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
