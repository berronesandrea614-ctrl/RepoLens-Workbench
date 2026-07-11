-- Feature D 包幻觉体检 P0 迁移
-- 新增表：dependency_check（依赖体检审计记录）
-- 注意：此脚本仅在已存在 repolens 数据库时执行；02_create_tables.sql 已含此表的完整 DDL。

USE repolens;

CREATE TABLE IF NOT EXISTS `dependency_check`
(
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    repo_id      BIGINT       NOT NULL,
    session_id   BIGINT       NULL,
    change_id    BIGINT       NULL COMMENT '关联 file_change_log.id',
    file_path    VARCHAR(512) NOT NULL,
    ecosystem    VARCHAR(16)  NOT NULL COMMENT 'npm / pypi',
    package_name VARCHAR(256) NOT NULL,
    version      VARCHAR(128) NULL,
    source       VARCHAR(16)  NOT NULL COMMENT 'MANIFEST / IMPORT',
    verdict      VARCHAR(16)  NOT NULL COMMENT 'OK / NOT_FOUND / TYPOSQUAT / UNKNOWN',
    detail_json  TEXT         NULL,
    checked_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_dc_repo_session (repo_id, session_id),
    INDEX idx_dc_change (change_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
