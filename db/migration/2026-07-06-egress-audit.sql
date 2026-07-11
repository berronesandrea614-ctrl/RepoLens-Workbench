-- G Feature: egress_log table (出网审计)
-- MySQL 8 plain. Live DB: apply this script manually before deploying G.
-- CREATE TABLE IF NOT EXISTS is OK per project convention.

CREATE TABLE IF NOT EXISTS `egress_log`
(
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    ts           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    purpose      VARCHAR(64)   NOT NULL COMMENT 'LLM/EMBEDDING/GIT_CLONE/DEP_CHECK',
    dest_host    VARCHAR(255)  NOT NULL,
    dest_port    INT           NULL,
    resolved_ip  VARCHAR(64)   NULL,
    is_loopback  TINYINT(1)    NOT NULL DEFAULT 0,
    allowed      TINYINT(1)    NOT NULL DEFAULT 1,
    privacy_mode VARCHAR(16)   NOT NULL COMMENT 'LOCAL_ONLY/ALLOWLIST/OPEN',
    model_name   VARCHAR(128)  NULL,
    bytes_out    BIGINT        NULL,
    KEY idx_el_ts (ts),
    KEY idx_el_allowed (allowed),
    KEY idx_el_dest_host (dest_host)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
