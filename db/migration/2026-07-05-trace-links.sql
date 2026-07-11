-- Feature C: Spec↔Implementation Traceability — extend requirement_symbol + add trace_snapshot
-- Apply to live DB manually before restart.

ALTER TABLE requirement_symbol
    ADD COLUMN link_type  VARCHAR(32)    NULL AFTER start_line,
    ADD COLUMN confidence DOUBLE         NOT NULL DEFAULT 1.0 AFTER link_type,
    ADD COLUMN status     VARCHAR(32)    NOT NULL DEFAULT 'LINKED' AFTER confidence,
    ADD COLUMN updated_at DATETIME       NULL AFTER status,
    ADD INDEX  idx_symbol (symbol_id);

CREATE TABLE IF NOT EXISTS trace_snapshot
(
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    repo_id        BIGINT         NOT NULL,
    coverage       DOUBLE         NOT NULL DEFAULT 0,
    orphan_count   INT            NOT NULL DEFAULT 0,
    dangling_count INT            NOT NULL DEFAULT 0,
    stale_count    INT            NOT NULL DEFAULT 0,
    detail_json    MEDIUMTEXT     NULL,
    degraded       TINYINT        NOT NULL DEFAULT 0,
    computed_at    DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_snapshot_repo (repo_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
