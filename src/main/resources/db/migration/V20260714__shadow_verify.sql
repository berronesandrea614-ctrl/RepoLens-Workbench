-- shadow_workspace: per-(repoId,sessionId) 影子工作区记录
CREATE TABLE IF NOT EXISTS shadow_workspace (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    repo_id     BIGINT       NOT NULL,
    session_id  BIGINT       NOT NULL,
    root_path   VARCHAR(512) NOT NULL COMMENT '影子区根目录绝对路径',
    strategy    VARCHAR(16)  NOT NULL COMMENT 'WORKTREE/CLONE_COW/COPY',
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/MERGED/DISCARDED',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_shadow_repo_session (repo_id, session_id),
    INDEX idx_shadow_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- verification_run: 每次 runVerification 的结构化结果
CREATE TABLE IF NOT EXISTS verification_run (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    repo_id          BIGINT       NOT NULL,
    session_id       BIGINT       NOT NULL,
    kind             VARCHAR(16)  NOT NULL COMMENT 'build/test',
    exit_code        INT          NOT NULL DEFAULT -1,
    passed           TINYINT(1)   NOT NULL DEFAULT 0,
    output_tail      TEXT         COMMENT '原始输出截尾',
    failures_json    TEXT         COMMENT 'JSON数组: [{file,line,symbol,message,context}]',
    network_isolated TINYINT(1)   NOT NULL DEFAULT 0,
    oracle_tampered  TINYINT(1)   NOT NULL DEFAULT 0,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_vrun_session (repo_id, session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- file_change_log 加 3 列（每条 ALTER 单独跑，不支持 IF NOT EXISTS，只跑一次）
ALTER TABLE file_change_log ADD COLUMN written_to_shadow TINYINT(1) NOT NULL DEFAULT 0;
ALTER TABLE file_change_log ADD COLUMN verify_status VARCHAR(16) NULL;
ALTER TABLE file_change_log ADD COLUMN verify_run_id BIGINT NULL;
