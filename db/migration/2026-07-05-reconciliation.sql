-- Feature B 计划vs实际对账 P1 迁移
-- 1. agent_run 新增 claimed_* 列（自报解析结果）
-- 2. 新建 requirement_reconciliation 快照表
-- 注意：此脚本仅在已存在 repolens 数据库时执行；02_create_tables.sql 已含完整 DDL。
-- live DB 手动 apply：mysql -u root -p repolens < 2026-07-05-reconciliation.sql

USE repolens;

-- 1. agent_run: 新增自报解析列（COLUMN NOT EXISTS 兼容）
ALTER TABLE `agent_run`
    ADD COLUMN IF NOT EXISTS `claimed_success`  TINYINT(1) NULL COMMENT 'AI 声称完成，1=是，null=未解析',
    ADD COLUMN IF NOT EXISTS `claimed_verified` TINYINT(1) NULL COMMENT 'AI 声称验证/测试通过，1=是，null=未解析',
    ADD COLUMN IF NOT EXISTS `claim_evidence`   VARCHAR(500) NULL COMMENT 'AI 声称的原话截段';

-- 2. requirement_reconciliation: 对账快照表（惰性计算，存完整 ledger JSON）
CREATE TABLE IF NOT EXISTS `requirement_reconciliation`
(
    id               BIGINT PRIMARY KEY AUTO_INCREMENT,
    requirement_id   BIGINT       NOT NULL COMMENT '关联 requirement.id',
    agent_run_id     BIGINT       NULL COMMENT '对账时对应的 agent_run.id（可为 null=无计划）',
    coverage         DECIMAL(5,4) NULL COMMENT '计划落实率 0.0~1.0',
    fidelity         DECIMAL(5,4) NULL COMMENT '改动契合度 0.0~1.0',
    off_plan_count   INT          NOT NULL DEFAULT 0,
    trust_flag       VARCHAR(16)  NOT NULL DEFAULT 'OK' COMMENT 'OK | SUSPECT | FABRICATED',
    ledger_json      MEDIUMTEXT   NULL COMMENT '完整 ReconciliationVO JSON 快照',
    computed_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_rr_req (requirement_id),
    INDEX idx_rr_computed (computed_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
