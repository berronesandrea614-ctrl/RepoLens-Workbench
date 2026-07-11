-- =====================================================================
-- 重写内核 · M1 数据地基：验证闭环 + 影子工作区
-- 分支：rewrite/kernel-from-scratch  ｜ 日期：2026-07-10
-- 单一事实源：本文件即 rk_* 表 DDL 的唯一权威（migration 副本与 E2E 测试均取此）。
-- 原则：从零重设，不沿用 DeepSeek 表；对标蓝图 §14（验证/自愈闭环）。
-- 避坑（对照旧表）：
--   1) 旧 file_change_log 用 MEDIUMTEXT 存改动全文 → 大文件撑爆 DB。本版只存 hash + diff 引用。
--   2) 旧 feature_ledger（failing-until-tested）根本没建表 = 假实现根源。本版补上 + tamper_seal 防篡改。
-- =====================================================================
USE repolens;

-- 影子工作区：agent 在此隔离副本真写盘，验证校验自己的改动。
CREATE TABLE IF NOT EXISTS rk_shadow_workspace (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    repo_id     BIGINT       NOT NULL,
    session_id  BIGINT       NOT NULL,
    run_id      BIGINT       NULL,
    root_path   VARCHAR(512) NOT NULL,
    base_commit VARCHAR(64)  NULL,
    strategy    VARCHAR(16)  NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_rk_shadow_repo_session (repo_id, session_id),
    INDEX idx_rk_shadow_run (run_id),
    INDEX idx_rk_shadow_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 文件改动记录：只存 hash + diff 引用，不存全文（避坑 1）。
CREATE TABLE IF NOT EXISTS rk_file_change (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    repo_id     BIGINT       NOT NULL,
    session_id  BIGINT       NOT NULL,
    run_id      BIGINT       NULL,
    shadow_id   BIGINT       NULL,
    file_path   VARCHAR(512) NOT NULL,
    op_type     VARCHAR(16)  NOT NULL,
    old_hash    CHAR(64)     NULL,
    new_hash    CHAR(64)     NULL,
    diff_ref    VARCHAR(512) NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'PROPOSED',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_rk_fc_repo_session (repo_id, session_id),
    INDEX idx_rk_fc_shadow (shadow_id),
    INDEX idx_rk_fc_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 验证运行：agent 在影子区跑编译/测试，结果落库（带 shadow_id 证明验的是自己的改动）。
CREATE TABLE IF NOT EXISTS rk_verification_run (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    repo_id          BIGINT      NOT NULL,
    session_id       BIGINT      NOT NULL,
    run_id           BIGINT      NULL,
    shadow_id        BIGINT      NULL,
    work_dir         VARCHAR(16) NOT NULL DEFAULT 'SHADOW',
    build_target     VARCHAR(16) NULL,
    kind             VARCHAR(16) NOT NULL,
    exit_code        INT         NOT NULL DEFAULT -1,
    passed           TINYINT(1)  NOT NULL DEFAULT 0,
    output_tail      TEXT        NULL,
    failures_json    TEXT        NULL,
    network_isolated TINYINT(1)  NOT NULL DEFAULT 0,
    oracle_tampered  TINYINT(1)  NOT NULL DEFAULT 0,
    created_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_rk_vrun_session (repo_id, session_id),
    INDEX idx_rk_vrun_shadow (shadow_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- failing-until-tested 特性清单：默认 FAILING，绿灯必须挂真凭据；tamper_seal 防偷改。
CREATE TABLE IF NOT EXISTS rk_feature_ledger (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    repo_id      BIGINT       NOT NULL,
    session_id   BIGINT       NOT NULL,
    run_id       BIGINT       NULL,
    feature_key  VARCHAR(128) NOT NULL,
    description  VARCHAR(512) NOT NULL,
    status       VARCHAR(12)  NOT NULL DEFAULT 'FAILING',
    test_ref     VARCHAR(256) NULL,
    evidence     TEXT         NULL,
    verification_id BIGINT    NULL,
    tamper_seal  CHAR(64)     NULL,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_rk_feature (repo_id, session_id, feature_key),
    INDEX idx_rk_feature_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- M5.3 会话 Checkpoint：给影子区代码 + 对话步序打快照，支持 rewind 回滚到某一点。
-- 避坑：不内联影子区全文——只记 shadow_snapshot_ref（快照落影子区 .rk/checkpoints/<id>/），
-- transcript_json 存当时对话（可空/可截断），rewind 时按 shadow_snapshot_ref 精确还原影子区。
CREATE TABLE IF NOT EXISTS rk_checkpoint (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    repo_id             BIGINT       NOT NULL,
    session_id          BIGINT       NOT NULL,
    run_id              BIGINT       NULL,
    shadow_id           BIGINT       NULL,
    label               VARCHAR(128) NULL,
    shadow_snapshot_ref VARCHAR(512) NULL COMMENT '影子区快照目录（相对影子根）',
    transcript_json     MEDIUMTEXT   NULL COMMENT '打点时的对话 JSON',
    step_index          INT          NOT NULL DEFAULT 0 COMMENT '打点时的对话步序',
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_rk_ckpt_session (repo_id, session_id),
    INDEX idx_rk_ckpt_shadow (shadow_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- M8 方案分支多方案对比（超越层 B7 后端引擎侧）：并行 N 个 AgentLoopExecutor 各自影子区隔离产真实指标，
-- 选定只合并选中分支、其余 DISCARDED。与旧 god class solution_branch 物理隔离（rk_* 命名空间）。
CREATE TABLE IF NOT EXISTS rk_solution_set (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    repo_id               BIGINT       NOT NULL,
    session_id            BIGINT       NOT NULL,
    question              VARCHAR(1000) NULL,
    repo_dir              VARCHAR(512) NOT NULL COMMENT '真目录根（select 合并锚点）',
    engine                VARCHAR(12)  NOT NULL DEFAULT 'NATIVE',
    status                VARCHAR(16)  NOT NULL DEFAULT 'GENERATING',
    variant_count         INT          NOT NULL DEFAULT 0,
    selected_branch_id    BIGINT       NULL,
    recommended_branch_id BIGINT       NULL,
    created_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_rk_ss_session (repo_id, session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS rk_solution_branch (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    set_id             BIGINT       NOT NULL,
    repo_id            BIGINT       NOT NULL,
    session_id         BIGINT       NOT NULL,
    run_id             BIGINT       NULL,
    shadow_id          BIGINT       NULL,
    label              VARCHAR(128) NOT NULL,
    strategy_hint      VARCHAR(512) NULL,
    variant_index      INT          NOT NULL DEFAULT 0,
    metric_kind        VARCHAR(8)   NOT NULL DEFAULT 'REAL',
    status             VARCHAR(12)  NOT NULL DEFAULT 'STAGED',
    files_changed      INT          NOT NULL DEFAULT 0,
    lines_added        INT          NOT NULL DEFAULT 0,
    lines_removed      INT          NOT NULL DEFAULT 0,
    tokens_spent       BIGINT       NOT NULL DEFAULT 0,
    turns              INT          NOT NULL DEFAULT 0,
    verified           BOOLEAN      NULL,
    termination_reason VARCHAR(24)  NULL,
    final_text         VARCHAR(2000) NULL,
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_rk_sb_set (set_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- M9 架构漂移时间维度（超越层 B8 后端引擎侧）：内核自抓调用图快照(图哈希当时间指纹+prev_hash审计链)，
-- 跨快照用语义稳定 key 做结构漂移比对，归因会话/commit。经只读端口 CallGraphSnapshotProvider 消费隔壁图，
-- 与隔壁 code_symbol/code_dependency/code_file 物理隔离（rk_* 命名空间）。
CREATE TABLE IF NOT EXISTS rk_graph_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT, repo_id BIGINT NOT NULL, session_id BIGINT NULL,
    seq INT NOT NULL, label VARCHAR(128) NULL, commit_ref VARCHAR(64) NULL,
    node_count INT NOT NULL DEFAULT 0, edge_count INT NOT NULL DEFAULT 0, file_count INT NOT NULL DEFAULT 0,
    graph_hash CHAR(64) NOT NULL, prev_hash CHAR(64) NULL,
    captured_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id), INDEX idx_rk_gs_repo (repo_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS rk_graph_snapshot_node (
    id BIGINT NOT NULL AUTO_INCREMENT, snapshot_id BIGINT NOT NULL, repo_id BIGINT NOT NULL,
    key_hash CHAR(64) NOT NULL, language VARCHAR(24) NULL, symbol_type VARCHAR(32) NULL,
    class_name VARCHAR(256) NULL, method_name VARCHAR(256) NULL, signature VARCHAR(1000) NULL,
    file_path VARCHAR(512) NULL, start_line INT NOT NULL DEFAULT 0, end_line INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id), INDEX idx_rk_gsn_snap (snapshot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS rk_graph_snapshot_edge (
    id BIGINT NOT NULL AUTO_INCREMENT, snapshot_id BIGINT NOT NULL, repo_id BIGINT NOT NULL,
    key_hash CHAR(64) NOT NULL, source_key_hash CHAR(64) NULL, target_name VARCHAR(512) NULL,
    relation_type VARCHAR(32) NULL, confidence DOUBLE NOT NULL DEFAULT 0,
    PRIMARY KEY (id), INDEX idx_rk_gse_snap (snapshot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS rk_graph_snapshot_file (
    id BIGINT NOT NULL AUTO_INCREMENT, snapshot_id BIGINT NOT NULL, repo_id BIGINT NOT NULL,
    file_path VARCHAR(512) NOT NULL, content_hash CHAR(64) NULL, last_commit_id VARCHAR(64) NULL,
    line_count INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id), INDEX idx_rk_gsf_snap (snapshot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS rk_arch_drift (
    id BIGINT NOT NULL AUTO_INCREMENT, repo_id BIGINT NOT NULL, from_snapshot_id BIGINT NOT NULL,
    to_snapshot_id BIGINT NOT NULL, drift_type VARCHAR(16) NOT NULL, entity_key_hash CHAR(64) NULL,
    entity_desc VARCHAR(1000) NULL, file_path VARCHAR(512) NULL, language VARCHAR(24) NULL,
    attributed_session_id BIGINT NULL, attributed_commit VARCHAR(64) NULL,
    detected_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id), INDEX idx_rk_drift_repo (repo_id, to_snapshot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
