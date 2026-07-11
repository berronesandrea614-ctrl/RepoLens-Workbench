-- Migration: agent_memory table upgrade (Wave E, 2026-07-04)
-- Adds memory_type, importance, confidence, last_accessed_at, access_count columns.
--
-- NOTE: MySQL 8 does not support ADD COLUMN IF NOT EXISTS.
-- Running this script on a database that already has these columns will produce
-- "Duplicate column name" errors which can be safely ignored.
-- For idempotent deploys, wrap each statement in a stored procedure that checks
-- information_schema.COLUMNS, or simply check + add only missing columns manually.

ALTER TABLE agent_memory
    ADD COLUMN memory_type      VARCHAR(32)   NOT NULL DEFAULT 'FACT' AFTER created_at;

ALTER TABLE agent_memory
    ADD COLUMN importance       TINYINT       NOT NULL DEFAULT 3 AFTER memory_type;

ALTER TABLE agent_memory
    ADD COLUMN confidence       DECIMAL(3, 2) NOT NULL DEFAULT 0.80 AFTER importance;

ALTER TABLE agent_memory
    ADD COLUMN last_accessed_at DATETIME      NULL AFTER confidence;

ALTER TABLE agent_memory
    ADD COLUMN access_count     INT           NOT NULL DEFAULT 0 AFTER last_accessed_at;
