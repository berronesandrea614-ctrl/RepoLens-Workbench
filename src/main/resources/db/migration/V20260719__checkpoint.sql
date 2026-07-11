CREATE TABLE IF NOT EXISTS checkpoint (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id      BIGINT NOT NULL,
    parent_id       BIGINT NULL COMMENT '父节点（树结构）',
    high_water_mark BIGINT NOT NULL COMMENT 'max(file_change_log.id) at this point',
    last_message_id BIGINT NULL COMMENT '最后一条 chat_message id',
    label           VARCHAR(128),
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session(session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
