CREATE TABLE IF NOT EXISTS session_context_note (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id  BIGINT NOT NULL,
    note_text   TEXT   NOT NULL COMMENT 'L3 session memory 笔记',
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session(session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS pinned_context_block (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id  BIGINT NOT NULL,
    block_id    VARCHAR(64) NOT NULL COMMENT 'LlmMessage meta.id',
    pinned      TINYINT(1) DEFAULT 1,
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_session_block(session_id, block_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
