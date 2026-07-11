ALTER TABLE agent_run_plan
    ADD COLUMN todo_json TEXT NULL COMMENT 'TodoWrite 清单 JSON',
    ADD COLUMN plan_version INT DEFAULT 1 COMMENT '计划版本号';

ALTER TABLE agent_run
    ADD COLUMN parent_run_id BIGINT NULL COMMENT '父 run（子代理用）',
    ADD COLUMN run_idx INT DEFAULT 0 COMMENT '子代理在父 run 中的序号';
