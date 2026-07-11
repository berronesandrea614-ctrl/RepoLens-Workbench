-- Migration: add op_type column to file_change_log
-- Date: 2026-07-04
-- Purpose: Explicit CREATE/WRITE discriminator so applyOne can route correctly even when
--          old_content is empty (e.g. overwriting a pre-existing empty file).
-- NOTE: This migration is NOT idempotent — running it twice on the same DB will error
--       with "Duplicate column name 'op_type'". Run exactly once per environment.
ALTER TABLE file_change_log ADD COLUMN op_type VARCHAR(16) NULL;
