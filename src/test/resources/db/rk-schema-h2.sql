-- 重写内核 M1 · rk_* 表的 H2 等价 DDL（仅供 E2E 用）。
-- 与 prod 权威 DDL（db/init/04_rewrite_kernel_M1.sql, MySQL）字段一一对应；
-- 差异仅为方言：去掉 ENGINE/CHARSET/内联 INDEX/ON UPDATE，TINYINT(1)→BOOLEAN，DATETIME→TIMESTAMP。

CREATE TABLE IF NOT EXISTS rk_shadow_workspace (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    repo_id     BIGINT       NOT NULL,
    session_id  BIGINT       NOT NULL,
    run_id      BIGINT,
    root_path   VARCHAR(512) NOT NULL,
    base_commit VARCHAR(64),
    strategy    VARCHAR(16)  NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS rk_file_change (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    repo_id     BIGINT       NOT NULL,
    session_id  BIGINT       NOT NULL,
    run_id      BIGINT,
    shadow_id   BIGINT,
    file_path   VARCHAR(512) NOT NULL,
    op_type     VARCHAR(16)  NOT NULL,
    old_hash    CHAR(64),
    new_hash    CHAR(64),
    diff_ref    VARCHAR(512),
    status      VARCHAR(20)  NOT NULL DEFAULT 'PROPOSED',
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS rk_verification_run (
    id               BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    repo_id          BIGINT      NOT NULL,
    session_id       BIGINT      NOT NULL,
    run_id           BIGINT,
    shadow_id        BIGINT,
    work_dir         VARCHAR(16) NOT NULL DEFAULT 'SHADOW',
    build_target     VARCHAR(16),
    kind             VARCHAR(16) NOT NULL,
    exit_code        INT         NOT NULL DEFAULT -1,
    passed           BOOLEAN     NOT NULL DEFAULT FALSE,
    output_tail      CLOB,
    failures_json    CLOB,
    network_isolated BOOLEAN     NOT NULL DEFAULT FALSE,
    oracle_tampered  BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS rk_feature_ledger (
    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    repo_id      BIGINT       NOT NULL,
    session_id   BIGINT       NOT NULL,
    run_id       BIGINT,
    feature_key  VARCHAR(128) NOT NULL,
    description  VARCHAR(512) NOT NULL,
    status       VARCHAR(12)  NOT NULL DEFAULT 'FAILING',
    test_ref     VARCHAR(256),
    evidence     CLOB,
    verification_id BIGINT,
    tamper_seal  CHAR(64),
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_rk_feature UNIQUE (repo_id, session_id, feature_key)
);

CREATE TABLE IF NOT EXISTS rk_checkpoint (
    id                  BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    repo_id             BIGINT       NOT NULL,
    session_id          BIGINT       NOT NULL,
    run_id              BIGINT,
    shadow_id           BIGINT,
    label               VARCHAR(128),
    shadow_snapshot_ref VARCHAR(512),
    transcript_json     CLOB,
    step_index          INT          NOT NULL DEFAULT 0,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- M8 方案分支多方案对比（H2 等价，供 E2E）。
CREATE TABLE IF NOT EXISTS rk_solution_set (
    id                    BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    repo_id               BIGINT        NOT NULL,
    session_id            BIGINT        NOT NULL,
    question              VARCHAR(1000),
    repo_dir              VARCHAR(512)  NOT NULL,
    engine                VARCHAR(12)   NOT NULL DEFAULT 'NATIVE',
    status                VARCHAR(16)   NOT NULL DEFAULT 'GENERATING',
    variant_count         INT           NOT NULL DEFAULT 0,
    selected_branch_id    BIGINT,
    recommended_branch_id BIGINT,
    created_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS rk_solution_branch (
    id                 BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    set_id             BIGINT        NOT NULL,
    repo_id            BIGINT        NOT NULL,
    session_id         BIGINT        NOT NULL,
    run_id             BIGINT,
    shadow_id          BIGINT,
    label              VARCHAR(128)  NOT NULL,
    strategy_hint      VARCHAR(512),
    variant_index      INT           NOT NULL DEFAULT 0,
    metric_kind        VARCHAR(8)    NOT NULL DEFAULT 'REAL',
    status             VARCHAR(12)   NOT NULL DEFAULT 'STAGED',
    files_changed      INT           NOT NULL DEFAULT 0,
    lines_added        INT           NOT NULL DEFAULT 0,
    lines_removed      INT           NOT NULL DEFAULT 0,
    tokens_spent       BIGINT        NOT NULL DEFAULT 0,
    turns              INT           NOT NULL DEFAULT 0,
    verified           BOOLEAN,
    termination_reason VARCHAR(24),
    final_text         VARCHAR(2000),
    created_at         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- M9 架构漂移时间维度（H2 等价，供 E2E）。
CREATE TABLE IF NOT EXISTS rk_graph_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, repo_id BIGINT NOT NULL, session_id BIGINT,
    seq INT NOT NULL, label VARCHAR(128), commit_ref VARCHAR(64),
    node_count INT NOT NULL DEFAULT 0, edge_count INT NOT NULL DEFAULT 0, file_count INT NOT NULL DEFAULT 0,
    graph_hash CHAR(64) NOT NULL, prev_hash CHAR(64),
    captured_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS rk_graph_snapshot_node (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, snapshot_id BIGINT NOT NULL, repo_id BIGINT NOT NULL,
    key_hash CHAR(64) NOT NULL, language VARCHAR(24), symbol_type VARCHAR(32),
    class_name VARCHAR(256), method_name VARCHAR(256), signature VARCHAR(1000),
    file_path VARCHAR(512), start_line INT NOT NULL DEFAULT 0, end_line INT NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS rk_graph_snapshot_edge (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, snapshot_id BIGINT NOT NULL, repo_id BIGINT NOT NULL,
    key_hash CHAR(64) NOT NULL, source_key_hash CHAR(64), target_name VARCHAR(512),
    relation_type VARCHAR(32), confidence DOUBLE NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS rk_graph_snapshot_file (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, snapshot_id BIGINT NOT NULL, repo_id BIGINT NOT NULL,
    file_path VARCHAR(512) NOT NULL, content_hash CHAR(64), last_commit_id VARCHAR(64),
    line_count INT NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS rk_arch_drift (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, repo_id BIGINT NOT NULL, from_snapshot_id BIGINT NOT NULL,
    to_snapshot_id BIGINT NOT NULL, drift_type VARCHAR(16) NOT NULL, entity_key_hash CHAR(64),
    entity_desc VARCHAR(1000), file_path VARCHAR(512), language VARCHAR(24),
    attributed_session_id BIGINT, attributed_commit VARCHAR(64),
    detected_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
