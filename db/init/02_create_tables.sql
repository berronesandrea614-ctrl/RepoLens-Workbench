USE repolens;

CREATE TABLE IF NOT EXISTS `user`
(
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    status        VARCHAR(32)  NOT NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_username (username),
    KEY idx_status (status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS workspace
(
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    name        VARCHAR(128) NOT NULL,
    owner_id    BIGINT       NOT NULL,
    description VARCHAR(512) NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_owner_id (owner_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS workspace_member
(
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    workspace_id BIGINT      NOT NULL,
    user_id      BIGINT      NOT NULL,
    role         VARCHAR(32) NOT NULL,
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_workspace_user (workspace_id, user_id),
    KEY idx_user_id (user_id),
    KEY idx_workspace_role (workspace_id, role)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS repo
(
    id               BIGINT PRIMARY KEY AUTO_INCREMENT,
    workspace_id     BIGINT       NOT NULL,
    repo_name        VARCHAR(255) NOT NULL,
    repo_url         VARCHAR(512) NOT NULL,
    branch_name      VARCHAR(255) NOT NULL,
    latest_commit_id VARCHAR(128) NULL,
    index_status     VARCHAR(32)  NOT NULL,
    created_by       BIGINT       NOT NULL,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_workspace_id (workspace_id),
    KEY idx_created_by (created_by)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS code_file
(
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    repo_id        BIGINT       NOT NULL,
    file_path      VARCHAR(512) NOT NULL,
    file_type      VARCHAR(64)  NOT NULL,
    content_hash   VARCHAR(128) NOT NULL,
    line_count     INT          NOT NULL DEFAULT 0,
    last_commit_id VARCHAR(128) NULL,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_repo_file_path (repo_id, file_path),
    KEY idx_repo_id (repo_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS code_symbol
(
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    repo_id     BIGINT        NOT NULL,
    file_id     BIGINT        NOT NULL,
    language    VARCHAR(32)   NOT NULL DEFAULT 'java' COMMENT '源语言: java/typescript/javascript…(多语言解析,Phase1)',
    symbol_type VARCHAR(32)   NOT NULL,
    class_name  VARCHAR(255)  NULL,
    method_name VARCHAR(255)  NULL,
    signature   VARCHAR(1024) NULL,
    api_path    VARCHAR(512)  NULL,
    http_method VARCHAR(16)   NULL,
    start_line  INT           NULL,
    end_line    INT           NULL,
    summary     VARCHAR(1024) NULL,
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_repo_class (repo_id, class_name),
    KEY idx_repo_method (repo_id, method_name),
    KEY idx_repo_api (repo_id, api_path),
    KEY idx_file_id (file_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS code_dependency
(
    id                 BIGINT PRIMARY KEY AUTO_INCREMENT,
    repo_id            BIGINT        NOT NULL,
    source_symbol_id   BIGINT        NOT NULL,
    target_symbol_name VARCHAR(512)  NOT NULL,
    relation_type      VARCHAR(64)   NOT NULL,
    confidence         DECIMAL(5, 4) NOT NULL DEFAULT 1.0000,
    created_at         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_repo_id (repo_id),
    KEY idx_source_symbol_id (source_symbol_id),
    KEY idx_target_symbol_name (target_symbol_name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS code_chunk
(
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    chunk_id      VARCHAR(128) NOT NULL,
    repo_id       BIGINT       NOT NULL,
    file_id       BIGINT       NOT NULL,
    symbol_id     BIGINT       NULL,
    chunk_type    VARCHAR(32)  NOT NULL,
    language      VARCHAR(32)  NOT NULL,
    file_path     VARCHAR(512) NOT NULL,
    start_line    INT          NULL,
    end_line      INT          NULL,
    content_hash  VARCHAR(128) NOT NULL,
    content       LONGTEXT     NOT NULL,
    summary       VARCHAR(1024) NULL,
    vector_status VARCHAR(32)  NOT NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_chunk_id (chunk_id),
    KEY idx_repo_id (repo_id),
    KEY idx_file_path (file_path),
    KEY idx_symbol_id (symbol_id),
    KEY idx_file_id (file_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS index_task
(
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    repo_id        BIGINT        NOT NULL,
    task_type      VARCHAR(32)   NOT NULL,
    status         VARCHAR(32)   NOT NULL,
    retry_count    INT           NOT NULL DEFAULT 0,
    max_retry      INT           NOT NULL DEFAULT 3,
    idempotent_key VARCHAR(128)  NOT NULL,
    error_msg      VARCHAR(2048) NULL,
    created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_idempotent_key (idempotent_key),
    KEY idx_status_updated_at (status, updated_at),
    KEY idx_repo_id (repo_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS chat_session
(
    id         BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id    BIGINT       NOT NULL,
    repo_id    BIGINT       NOT NULL,
    title      VARCHAR(255) NOT NULL,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_user_id (user_id),
    KEY idx_repo_id (repo_id),
    KEY idx_created_at (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS chat_message
(
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id      BIGINT      NOT NULL,
    role            VARCHAR(32) NOT NULL,
    content         LONGTEXT    NOT NULL,
    references_json MEDIUMTEXT  NULL,
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_session_id (session_id),
    KEY idx_created_at (created_at),
    KEY idx_session_id_created_at (session_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS llm_call_log
(
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id       BIGINT       NOT NULL,
    repo_id       BIGINT       NOT NULL,
    session_id    BIGINT       NULL,
    model_name    VARCHAR(128) NOT NULL,
    prompt_hash   VARCHAR(128) NOT NULL,
    response_hash VARCHAR(128) NULL,
    token_input   INT          NOT NULL DEFAULT 0,
    token_output  INT          NOT NULL DEFAULT 0,
    cost_ms       BIGINT       NOT NULL DEFAULT 0,
    success       TINYINT(1)   NOT NULL DEFAULT 0,
    error_code    VARCHAR(64)  NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_user_id (user_id),
    KEY idx_repo_id (repo_id),
    KEY idx_session_id (session_id),
    KEY idx_created_at (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS tool_call_log
(
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    repo_id     BIGINT       NOT NULL,
    session_id  BIGINT       NULL,
    tool_name   VARCHAR(64)  NOT NULL,
    input_json  MEDIUMTEXT   NULL,
    output_json MEDIUMTEXT   NULL,
    success     TINYINT(1)   NOT NULL DEFAULT 0,
    cost_ms     BIGINT       NOT NULL DEFAULT 0,
    error_msg   VARCHAR(2048) NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_user_id (user_id),
    KEY idx_repo_id (repo_id),
    KEY idx_session_id (session_id),
    KEY idx_created_at (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS agent_memory
(
    id                BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id           BIGINT         NOT NULL,
    repo_id           BIGINT         NOT NULL,
    content           VARCHAR(1000)  NOT NULL,
    keywords          VARCHAR(255)   NULL,
    source_session_id BIGINT         NULL,
    created_at        DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    memory_type       VARCHAR(32)    NOT NULL DEFAULT 'FACT',
    importance        TINYINT        NOT NULL DEFAULT 3,
    confidence        DECIMAL(3, 2)  NOT NULL DEFAULT 0.80,
    last_accessed_at  DATETIME       NULL,
    access_count      INT            NOT NULL DEFAULT 0,
    INDEX idx_user_repo (user_id, repo_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS requirement
(
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id       BIGINT        NOT NULL,
    repo_id       BIGINT        NOT NULL,
    session_id    BIGINT        NULL,
    agent_run_id  BIGINT        NULL,
    title         VARCHAR(255)  NOT NULL,
    summary       VARCHAR(1000) NULL,
    approach      VARCHAR(500)  NULL,
    status        VARCHAR(32)   NOT NULL,
    source        VARCHAR(32)   NOT NULL DEFAULT 'code',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME      NULL,
    INDEX idx_req_repo (repo_id, user_id),
    INDEX idx_req_source_updated (repo_id, user_id, source, updated_at),
    INDEX idx_req_agent_run (agent_run_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS requirement_symbol
(
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    requirement_id BIGINT         NOT NULL,
    symbol_id      BIGINT         NULL,
    file_path      VARCHAR(512)   NULL,
    start_line     INT            NULL,
    link_type      VARCHAR(32)    NULL,
    confidence     DOUBLE         NOT NULL DEFAULT 1.0,
    status         VARCHAR(32)    NOT NULL DEFAULT 'LINKED',
    updated_at     DATETIME       NULL,
    INDEX idx_reqsym (requirement_id),
    INDEX idx_symbol (symbol_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS file_change_log
(
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    repo_id     BIGINT       NOT NULL,
    session_id  BIGINT       NULL,
    file_path   VARCHAR(512) NOT NULL,
    old_content MEDIUMTEXT   NULL,
    new_content MEDIUMTEXT   NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reverted    TINYINT      NOT NULL DEFAULT 0,
    status      VARCHAR(16)  NOT NULL DEFAULT 'APPLIED',
    op_type     VARCHAR(16)  NULL,
    INDEX idx_fcl (repo_id, session_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS agent_run
(
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    repo_id        BIGINT        NOT NULL,
    session_id     BIGINT        NULL,
    user_id        BIGINT        NULL,
    question       VARCHAR(2000) NULL,
    mode           VARCHAR(16)   NULL,
    answer_preview VARCHAR(500)  NULL,
    iterations     INT           NULL,
    tool_calls     INT           NULL,
    status         VARCHAR(16)   NULL,
    created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ar_repo (repo_id, session_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS agent_run_step
(
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_id       BIGINT        NOT NULL,
    step_index   INT           NULL,
    type         VARCHAR(16)   NULL,
    tool_name    VARCHAR(64)   NULL,
    tool_args    TEXT          NULL,
    thought      TEXT          NULL,
    observation  MEDIUMTEXT    NULL,
    target_files VARCHAR(1000) NULL,
    status       VARCHAR(16)   NULL,
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ars_run (run_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

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

CREATE TABLE IF NOT EXISTS app_setting
(
    k          VARCHAR(64) PRIMARY KEY,
    v          VARCHAR(1024) NULL,
    updated_at DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 用户账号（真实鉴权 Wave G）：admin 首启 seed（id=1 对齐历史数据），密码 BCrypt 存储。
CREATE TABLE IF NOT EXISTS `user_account` (
    id            BIGINT PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name  VARCHAR(128) NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ua_username (username)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 依赖体检记录（Feature D 包幻觉体检 P0）
-- 每条对应一个 AI 新增依赖的体检结论（OK/NOT_FOUND/TYPOSQUAT/UNKNOWN）。
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

-- 出网审计日志（Feature G 代码0字节出网可验证）
-- 每次应用层出站连接均留一条审计记录（allowed=0 即被拦截）。
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

-- 理解债务物化表（Feature A 理解债务仪表盘 MVP）
-- 每 (repo_id, file_id) 一行，存七信号快照与综合评分。
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

-- Feature C: 可追溯度快照（每个 repo 最多一行，惰性重算）
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

-- shadow_workspace（自研 AI 内核 §1）
CREATE TABLE IF NOT EXISTS shadow_workspace (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    repo_id     BIGINT       NOT NULL,
    session_id  BIGINT       NOT NULL,
    root_path   VARCHAR(512) NOT NULL,
    strategy    VARCHAR(16)  NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_shadow_repo_session (repo_id, session_id),
    INDEX idx_shadow_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- verification_run（自研 AI 内核 §1）
CREATE TABLE IF NOT EXISTS verification_run (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    repo_id          BIGINT      NOT NULL,
    session_id       BIGINT      NOT NULL,
    kind             VARCHAR(16) NOT NULL,
    exit_code        INT         NOT NULL DEFAULT -1,
    passed           TINYINT(1)  NOT NULL DEFAULT 0,
    output_tail      TEXT,
    failures_json    TEXT,
    network_isolated TINYINT(1)  NOT NULL DEFAULT 0,
    oracle_tampered  TINYINT(1)  NOT NULL DEFAULT 0,
    created_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_vrun_session (repo_id, session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
