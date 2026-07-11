-- CC-6: Add source column to requirement table
-- Values: 'code' (default, from AI session code mode) | 'external' (from Claude Code file-watcher)
ALTER TABLE requirement
    ADD COLUMN source VARCHAR(32) NOT NULL DEFAULT 'code'
        AFTER status;
