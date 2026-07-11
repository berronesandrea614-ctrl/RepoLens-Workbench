CREATE TABLE IF NOT EXISTS `user_account` (
    id           BIGINT PRIMARY KEY,
    username     VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(128) NULL,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ua_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
