-- F Feature: AI contribution provenance ledger
-- MySQL 8 plain. Live DB: apply this script manually before deploying F.
-- All new columns are nullable for backwards compatibility with existing rows.

-- 1. New table: ai_contribution_record (tamper-evident hash chain ledger)
CREATE TABLE IF NOT EXISTS `ai_contribution_record`
(
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    repo_id        BIGINT        NOT NULL,
    seq            BIGINT        NOT NULL COMMENT '单调递增 per repo，哈希链序号',
    change_id      BIGINT        NULL COMMENT 'file_change_log.id',
    llm_call_id    BIGINT        NULL COMMENT 'llm_call_log.id',
    agent_run_id   BIGINT        NULL COMMENT 'agent_run.id',
    provider       VARCHAR(64)   NULL COMMENT 'LLM provider (deepseek/ollama/openai…)',
    model_name     VARCHAR(128)  NULL,
    model_version  VARCHAR(128)  NULL,
    prompt_hash    VARCHAR(64)   NULL COMMENT 'SHA-256 of the prompt text',
    context_hash   VARCHAR(64)   NULL COMMENT 'SHA-256 of the retrieval context',
    prompt_snapshot TEXT         NULL COMMENT '明文 prompt，仅 audit.capture-plaintext=true 时写入',
    file_path      VARCHAR(512)  NULL,
    diff_hash      VARCHAR(64)   NULL COMMENT 'SHA-256(oldContent+newContent)',
    decision       VARCHAR(16)   NOT NULL COMMENT 'APPROVED/REJECTED/REVERTED',
    approver_id    BIGINT        NULL,
    decided_at     DATETIME      NOT NULL,
    prev_hash      VARCHAR(64)   NOT NULL DEFAULT '0000000000000000000000000000000000000000000000000000000000000000',
    record_hash    VARCHAR(64)   NOT NULL,
    KEY idx_acr_repo_seq (repo_id, seq),
    KEY idx_acr_change_id (change_id),
    KEY idx_acr_decided_at (decided_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 2. Extend file_change_log with provenance columns
ALTER TABLE `file_change_log`
    ADD COLUMN IF NOT EXISTS `llm_call_id`  BIGINT   NULL COMMENT 'llm_call_log.id',
    ADD COLUMN IF NOT EXISTS `agent_run_id` BIGINT   NULL COMMENT 'agent_run.id',
    ADD COLUMN IF NOT EXISTS `approved_by`  BIGINT   NULL COMMENT 'user_id of approver',
    ADD COLUMN IF NOT EXISTS `approved_at`  DATETIME NULL COMMENT 'timestamp of approval/rejection/revert';

-- 3. Extend llm_call_log with trace columns
ALTER TABLE `llm_call_log`
    ADD COLUMN IF NOT EXISTS `provider`      VARCHAR(64)  NULL COMMENT 'LLM provider',
    ADD COLUMN IF NOT EXISTS `model_version` VARCHAR(128) NULL COMMENT 'full model version string',
    ADD COLUMN IF NOT EXISTS `context_hash`  VARCHAR(64)  NULL COMMENT 'SHA-256 of retrieval context';
