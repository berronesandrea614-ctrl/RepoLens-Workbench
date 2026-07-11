-- 重写内核 M8 · 方案分支多方案对比（超越层 B7 后端引擎侧）。
-- 一句话：同一个「有多种合理实现」的任务，并行跑 N 个 AgentLoopExecutor（不同 strategy_hint、
-- 各自独立影子区隔离，均不落盘），产出真实 staged 改动 + 真实指标（改动面/行数增删/token/轮次），
-- 打分推荐；用户选定后只把选中分支合并回真目录，其余分支 DISCARDED（零副作用）。
--
-- 与旧 god class 的 K 版 solution_branch 物理隔离：这里是 rk_* 命名空间、纯内核引擎侧，
-- 不碰旧 solution_branch/SolutionBranchService，也不碰 code_symbol/code_dependency（隔壁窗口契约）。

CREATE TABLE IF NOT EXISTS rk_solution_set (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    repo_id               BIGINT       NOT NULL,
    session_id            BIGINT       NOT NULL,
    question              VARCHAR(1000) NULL COMMENT '触发多方案的原始任务',
    repo_dir              VARCHAR(512) NOT NULL COMMENT '真目录根（select 合并锚点，自证不依赖外部解析）',
    engine                VARCHAR(12)  NOT NULL DEFAULT 'NATIVE' COMMENT 'NATIVE(自研并行staged) | CLAUDE(预测)',
    status                VARCHAR(16)  NOT NULL DEFAULT 'GENERATING' COMMENT 'GENERATING/READY/SELECTED/DISCARDED/FAILED',
    variant_count         INT          NOT NULL DEFAULT 0 COMMENT '实际产出的方案分支数',
    selected_branch_id    BIGINT       NULL,
    recommended_branch_id BIGINT       NULL,
    created_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_rk_ss_session (repo_id, session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 每个方案一行。run_id/shadow_id 指向该分支独占的 agent run 与影子区（隔离基石）；
-- 指标全部来自真实 staged 改动（rk_file_change 按 shadow_id 归集），metric_kind=REAL 诚实标注。
CREATE TABLE IF NOT EXISTS rk_solution_branch (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    set_id             BIGINT       NOT NULL,
    repo_id            BIGINT       NOT NULL,
    session_id         BIGINT       NOT NULL,
    run_id             BIGINT       NULL COMMENT '本分支独占 run（= 本行 id，自命名空间避免与真 agent_run 冲突）',
    shadow_id          BIGINT       NULL COMMENT '本分支独占影子区（隔离，不落盘）',
    label              VARCHAR(128) NOT NULL COMMENT '方案名（如「中间件限流」）',
    strategy_hint      VARCHAR(512) NULL COMMENT '喂给该分支的架构策略提示（制造多样性）',
    variant_index      INT          NOT NULL DEFAULT 0,
    metric_kind        VARCHAR(8)   NOT NULL DEFAULT 'REAL' COMMENT 'REAL(自研真实staged) | PREDICTED(Claude静态预估)',
    status             VARCHAR(12)  NOT NULL DEFAULT 'STAGED' COMMENT 'STAGED/SELECTED/DISCARDED/FAILED',
    files_changed      INT          NOT NULL DEFAULT 0,
    lines_added        INT          NOT NULL DEFAULT 0,
    lines_removed      INT          NOT NULL DEFAULT 0,
    tokens_spent       BIGINT       NOT NULL DEFAULT 0,
    turns              INT          NOT NULL DEFAULT 0,
    verified           BOOLEAN      NULL COMMENT '静态/真跑验证结论；P1 未跑为 NULL（诚实：不谎报绿）',
    termination_reason VARCHAR(24)  NULL,
    final_text         VARCHAR(2000) NULL COMMENT '该分支 agent 的收尾说明（截断）',
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_rk_sb_set (set_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
