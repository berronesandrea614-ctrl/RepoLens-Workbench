-- 迁移：需求意图可视化数据层（Insight-T1）
-- 1. requirement 表补 agent_run_id + approach 列
-- 2. 新增 agent_run_plan 表（结构化计划落库）

USE repolens;

-- 1a. requirement 表：关联本轮 agent run（无 agent run 的纯 RAG 路径为 null）
ALTER TABLE requirement
    ADD COLUMN IF NOT EXISTS agent_run_id BIGINT NULL AFTER session_id,
    ADD COLUMN IF NOT EXISTS approach     VARCHAR(500) NULL AFTER summary;

-- 1b. 为 agent_run_id 加索引（IF NOT EXISTS MySQL 8.0.22+）
ALTER TABLE requirement
    ADD INDEX IF NOT EXISTS idx_req_agent_run (agent_run_id);

-- 2. agent_run_plan：每条 agent_run（code 模式启用规划时）对应一份结构化计划
CREATE TABLE IF NOT EXISTS agent_run_plan
(
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    agent_run_id BIGINT       NOT NULL,
    approach     VARCHAR(500) NULL,
    plan_json    MEDIUMTEXT   NULL,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_plan_run (agent_run_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
