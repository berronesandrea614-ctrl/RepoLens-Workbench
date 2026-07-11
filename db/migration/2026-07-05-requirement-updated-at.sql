-- B1 fix: add updated_at to requirement for external-changes merge window.
-- Null = never updated (use created_at as fallback for merge window check).
ALTER TABLE requirement
    ADD COLUMN updated_at DATETIME NULL AFTER source;

-- Index to speed up "find recent external requirement for (repo_id, user_id)" query.
ALTER TABLE requirement
    ADD INDEX idx_req_source_updated (repo_id, user_id, source, updated_at);
