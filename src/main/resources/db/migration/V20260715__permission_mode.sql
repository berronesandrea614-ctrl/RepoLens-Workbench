ALTER TABLE agent_run
    ADD COLUMN permission_mode VARCHAR(20) NULL COMMENT '权限模式 DEFAULT/PLAN/ACCEPT_EDITS/AUTO/BYPASS',
    ADD COLUMN source_plan_run_id BIGINT NULL COMMENT '来自哪个 PLAN run',
    ADD COLUMN tool_turns INT DEFAULT 0 COMMENT '工具执行轮数',
    ADD COLUMN wall_clock_ms BIGINT NULL COMMENT '墙钟耗时',
    ADD COLUMN prompt_tokens INT NULL,
    ADD COLUMN completion_tokens INT NULL;

ALTER TABLE file_change_log
    ADD COLUMN apply_strategy VARCHAR(20) NULL COMMENT 'AUTO_APPLY/STAGE/BLOCK',
    ADD COLUMN risk_level VARCHAR(5) NULL COMMENT 'A/B/C/D/E',
    ADD COLUMN auto_applied TINYINT(1) DEFAULT 0;

ALTER TABLE agent_run_step
    ADD COLUMN permission_verdict VARCHAR(20) NULL,
    ADD COLUMN apply_strategy VARCHAR(20) NULL,
    ADD COLUMN risk_level VARCHAR(5) NULL;
