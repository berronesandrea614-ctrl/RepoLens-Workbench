-- Feature A: 理解债务仪表盘 MVP 迁移
-- 手动 apply，MySQL8 不支持 ADD COLUMN IF NOT EXISTS，执行前请确认列不存在。
-- 顺序：
--   1. 新增物化表 comprehension_debt_file
--   2. code_symbol 加 cyclomatic / cognitive 列
--   3. file_change_log 加复核四列

USE repolens;

-- 1. 理解债务物化表
CREATE TABLE IF NOT EXISTS `comprehension_debt_file`
(
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    repo_id         BIGINT       NOT NULL,
    file_id         BIGINT       NOT NULL,
    file_path       VARCHAR(512) NOT NULL,
    score           INT          NOT NULL DEFAULT 0,
    band            VARCHAR(8)   NOT NULL DEFAULT 'GREEN' COMMENT 'RED/YELLOW/GREEN',
    s1_ai_ratio     DOUBLE       NOT NULL DEFAULT 0.0,
    s2_unreviewed   DOUBLE       NOT NULL DEFAULT 0.0,
    s3_no_rationale DOUBLE       NOT NULL DEFAULT 0.0,
    s4_complexity   DOUBLE       NOT NULL DEFAULT 0.0,
    s5_churn        DOUBLE       NOT NULL DEFAULT 0.0,
    s6_recency      DOUBLE       NOT NULL DEFAULT 0.5 COMMENT 'MVP固定中性值0.5',
    s7_coverage     DOUBLE       NOT NULL DEFAULT 0.0,
    amp_factor      DOUBLE       NOT NULL DEFAULT 1.0,
    signals_json    TEXT         NULL,
    degraded        TINYINT(1)   NOT NULL DEFAULT 0,
    stale           TINYINT(1)   NOT NULL DEFAULT 0,
    computed_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_repo_file (repo_id, file_id),
    KEY idx_repo_score (repo_id, score),
    KEY idx_repo_band (repo_id, band)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 2. code_symbol 复杂度列（执行前确认不存在）
ALTER TABLE code_symbol
    ADD COLUMN cyclomatic INT NULL DEFAULT 0,
    ADD COLUMN cognitive INT NULL DEFAULT 0;

-- 3. file_change_log 复核列（执行前确认不存在）
ALTER TABLE file_change_log
    ADD COLUMN reviewed_at  DATETIME     NULL,
    ADD COLUMN review_type  VARCHAR(16)  NULL COMMENT 'DIFF_VIEWED / ACCEPTED / QUIZZED',
    ADD COLUMN dwell_ms     BIGINT       NULL,
    ADD COLUMN quiz_score   INT          NULL;
