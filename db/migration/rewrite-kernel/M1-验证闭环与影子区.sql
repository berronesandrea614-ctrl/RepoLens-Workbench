-- =====================================================================
-- 重写内核 · M1 数据地基：验证闭环 + 影子工作区
-- 分支：rewrite/kernel-from-scratch  ｜ 日期：2026-07-10
-- 原则：从零重设，不沿用 DeepSeek 表；对标蓝图 §14（验证/自愈闭环）。
-- 避坑（对照 archive/stage1-2-green 旧表）：
--   1) 旧 file_change_log 用 MEDIUMTEXT 存改动全文 → 大文件撑爆 DB。
--      本版只存 hash + diff 引用，全文落磁盘/影子区。
--   2) 旧版 feature_ledger（failing-until-tested）根本没建表 = 假实现。
--      本版补上，且加 tamper_seal 防模型篡改。
-- =====================================================================

-- 影子工作区：agent 在此隔离副本真写盘，验证校验自己的改动。
CREATE TABLE IF NOT EXISTS rk_shadow_workspace (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    repo_id     BIGINT       NOT NULL,
    session_id  BIGINT       NOT NULL,
    run_id      BIGINT       NULL,                       -- 关联 agent run（旧版缺，导致改动归属不清）
    root_path   VARCHAR(512) NOT NULL,
    base_commit VARCHAR(64)  NULL,                       -- 基线 commit，回滚/对拍锚点（旧版缺）
    strategy    VARCHAR(16)  NOT NULL,                   -- CLONE_COW / OVERLAY / COPY
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE / MERGED / DISCARDED
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
    shadow_id   BIGINT       NULL,                       -- 落到哪个影子区
    file_path   VARCHAR(512) NOT NULL,
    op_type     VARCHAR(16)  NOT NULL,                   -- WRITE / CREATE / DELETE / RENAME
    old_hash    CHAR(64)     NULL,                       -- 旧内容 sha256（读后编不变式校验用）
    new_hash    CHAR(64)     NULL,                       -- 新内容 sha256
    diff_ref    VARCHAR(512) NULL,                       -- diff/全文外部存储引用，非内联全文
    status      VARCHAR(20)  NOT NULL DEFAULT 'PROPOSED',-- PROPOSED/WRITTEN_TO_SHADOW/MERGED/REVERTED/DISCARDED
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_rk_fc_repo_session (repo_id, session_id),
    INDEX idx_rk_fc_shadow (shadow_id),
    INDEX idx_rk_fc_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 验证运行：agent 在影子区跑编译/测试，结果落库（保留旧版好设计 + 补关联）。
CREATE TABLE IF NOT EXISTS rk_verification_run (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    repo_id          BIGINT      NOT NULL,
    session_id       BIGINT      NOT NULL,
    run_id           BIGINT      NULL,
    shadow_id        BIGINT      NULL,                    -- 验证的是哪个影子区（旧版缺，无法证明"验的是自己的改动"）
    work_dir         VARCHAR(16) NOT NULL DEFAULT 'SHADOW',-- SHADOW / REPO（回退时标注）
    build_target     VARCHAR(16) NULL,                    -- maven/gradle/npm/python/go/rust
    kind             VARCHAR(16) NOT NULL,                -- COMPILE / TEST / LINT
    exit_code        INT         NOT NULL DEFAULT -1,
    passed           TINYINT(1)  NOT NULL DEFAULT 0,
    output_tail      TEXT        NULL,
    failures_json    TEXT        NULL,                    -- 结构化失败（喂回模型自愈）
    network_isolated TINYINT(1)  NOT NULL DEFAULT 0,      -- 断网防 reward hacking
    oracle_tampered  TINYINT(1)  NOT NULL DEFAULT 0,      -- oracle 文件被篡改标记
    created_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_rk_vrun_session (repo_id, session_id),
    INDEX idx_rk_vrun_shadow (shadow_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- failing-until-tested 特性清单（旧版缺失 = 假实现根源）。
-- 模型不可标 PASSING，除非挂上真实测试证据；tamper_seal 防篡改。
CREATE TABLE IF NOT EXISTS rk_feature_ledger (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    repo_id      BIGINT       NOT NULL,
    session_id   BIGINT       NOT NULL,
    run_id       BIGINT       NULL,
    feature_key  VARCHAR(128) NOT NULL,                  -- 特性稳定标识
    description  VARCHAR(512) NOT NULL,
    status       VARCHAR(12)  NOT NULL DEFAULT 'FAILING',-- FAILING / PASSING（默认 failing，铁律）
    test_ref     VARCHAR(256) NULL,                      -- 关联的真实测试标识
    evidence     TEXT         NULL,                      -- 通过证据：真实测试输出 + exit code
    verification_id BIGINT    NULL,                       -- 关联 rk_verification_run（绿灯挂真凭据）
    tamper_seal  CHAR(64)     NULL,                       -- sha256(key+status+evidence)，防模型偷改状态
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_rk_feature (repo_id, session_id, feature_key),
    INDEX idx_rk_feature_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
