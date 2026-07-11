-- 重写内核 M9 · 架构漂移时间维度（超越层 B8 后端引擎侧）。
-- 一句话：隔壁窗口的调用图无历史（每次索引全量覆盖只留当前一份），内核在此自己「沿时间抓快照」——
-- 每次会话/按需通过只读端口 CallGraphSnapshotProvider 拉当前整张图，算语义稳定 key + 图哈希（当时间指纹，
-- prev_hash 串成审计链），落 rk_ 快照表；跨快照做结构漂移比对（符号/依赖/文件 增删改），归因到引入它的会话/commit。
--
-- 契约：内核对隔壁 code_symbol/code_dependency/code_file 表与其 VO/api 零直接引用，只经 CallGraphSnapshotProvider
-- 只读消费。本组 rk_* 表全在内核 zone，与隔壁 schema 物理隔离。

-- 一次图快照（某时刻当前态的全量投影 + 图哈希/审计链）。
CREATE TABLE IF NOT EXISTS rk_graph_snapshot (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    repo_id     BIGINT       NOT NULL,
    session_id  BIGINT       NULL COMMENT '哪个会话抓的（漂移归因锚点之一）',
    seq         INT          NOT NULL COMMENT '本 repo 内快照序号(1起)，回放演化按它排',
    label       VARCHAR(128) NULL,
    commit_ref  VARCHAR(64)  NULL COMMENT '代表性 commit（各文件 last_commit_id 唯一时取之，否则空）',
    node_count  INT          NOT NULL DEFAULT 0,
    edge_count  INT          NOT NULL DEFAULT 0,
    file_count  INT          NOT NULL DEFAULT 0,
    graph_hash  CHAR(64)     NOT NULL COMMENT '整张图确定性 sha256（排序后的节点key+边key），当时间指纹',
    prev_hash   CHAR(64)     NULL COMMENT '前一快照的 graph_hash，串成防篡改审计链',
    captured_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_rk_gs_repo (repo_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 快照内的符号节点。key_hash = sha256(语义稳定key)，跨快照用它认同一个符号（不认会变的自增 id）。
CREATE TABLE IF NOT EXISTS rk_graph_snapshot_node (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    snapshot_id BIGINT       NOT NULL,
    repo_id     BIGINT       NOT NULL,
    key_hash    CHAR(64)     NOT NULL COMMENT 'sha256(language|filePath|symbolType|className|methodName|signature)',
    language    VARCHAR(24)  NULL,
    symbol_type VARCHAR(32)  NULL,
    class_name  VARCHAR(256) NULL,
    method_name VARCHAR(256) NULL,
    signature   VARCHAR(1000) NULL,
    file_path   VARCHAR(512) NULL,
    start_line  INT          NOT NULL DEFAULT 0,
    end_line    INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    INDEX idx_rk_gsn_snap (snapshot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 快照内的依赖边。key_hash = sha256(源稳定key -> 目标名 : 关系)。
CREATE TABLE IF NOT EXISTS rk_graph_snapshot_edge (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    snapshot_id     BIGINT       NOT NULL,
    repo_id         BIGINT       NOT NULL,
    key_hash        CHAR(64)     NOT NULL COMMENT 'sha256(sourceStableKey -> targetName : relationType)',
    source_key_hash CHAR(64)     NULL COMMENT '源符号节点的 key_hash',
    target_name     VARCHAR(512) NULL,
    relation_type   VARCHAR(32)  NULL,
    confidence      DOUBLE       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    INDEX idx_rk_gse_snap (snapshot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 快照内的文件指纹（content_hash 判文件是否变、last_commit_id 当归因锚点）。
CREATE TABLE IF NOT EXISTS rk_graph_snapshot_file (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    snapshot_id    BIGINT       NOT NULL,
    repo_id        BIGINT       NOT NULL,
    file_path      VARCHAR(512) NOT NULL,
    content_hash   CHAR(64)     NULL,
    last_commit_id VARCHAR(64)  NULL,
    line_count     INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    INDEX idx_rk_gsf_snap (snapshot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 两快照间检出的一处架构漂移（结果表，前端时间轴读它）。
CREATE TABLE IF NOT EXISTS rk_arch_drift (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    repo_id               BIGINT       NOT NULL,
    from_snapshot_id      BIGINT       NOT NULL,
    to_snapshot_id        BIGINT       NOT NULL,
    drift_type            VARCHAR(16)  NOT NULL COMMENT 'NODE_ADDED/NODE_REMOVED/EDGE_ADDED/EDGE_REMOVED/FILE_ADDED/FILE_REMOVED/FILE_CHANGED',
    entity_key_hash       CHAR(64)     NULL,
    entity_desc           VARCHAR(1000) NULL COMMENT '人读的漂移主体（如 com.demo.Calc#add(int,int) 或文件路径）',
    file_path             VARCHAR(512) NULL,
    language              VARCHAR(24)  NULL,
    attributed_session_id BIGINT       NULL COMMENT '引入这处漂移的会话（to 快照的 session）',
    attributed_commit     VARCHAR(64)  NULL COMMENT '引入这处漂移的 commit',
    detected_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_rk_drift_repo (repo_id, to_snapshot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
