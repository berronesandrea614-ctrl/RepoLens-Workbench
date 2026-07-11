CREATE TABLE IF NOT EXISTS feishu_binding (
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id        BIGINT       NOT NULL,
    repo_id        BIGINT       NOT NULL COMMENT '绑定的 Claude 窗口(项目)',
    session_id     BIGINT       NULL,
    bot_name       VARCHAR(64)  NULL COMMENT '展示名',
    app_id         VARCHAR(64)  NOT NULL COMMENT '飞书 App ID',
    app_secret_enc VARCHAR(512) NOT NULL COMMENT 'App Secret AES-GCM 密文(绝不明文)',
    status         VARCHAR(16)  NOT NULL DEFAULT 'DISCONNECTED' COMMENT 'CONNECTED/DISCONNECTED/ERROR',
    last_error     VARCHAR(512) NULL,
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_fb_appid (app_id),
    KEY idx_fb_repo (repo_id),
    KEY idx_fb_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
