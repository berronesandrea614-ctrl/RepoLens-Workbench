-- RepoLens acceptance SQL
USE repolens;

-- 1) verify table count (expected 13)
SELECT COUNT(*) AS table_count
FROM information_schema.tables
WHERE table_schema = 'repolens' AND table_type = 'BASE TABLE';

SHOW TABLES;

-- 2) latest repos
SELECT id, workspace_id, repo_name, branch_name, index_status, latest_commit_id, created_by, created_at
FROM repo
ORDER BY id DESC
LIMIT 10;

-- 3) latest index tasks
SELECT id, repo_id, task_type, status, retry_count, max_retry, idempotent_key, error_msg, updated_at
FROM index_task
ORDER BY id DESC
LIMIT 30;

-- 4) code_file summary
SELECT repo_id, file_type, COUNT(*) AS cnt
FROM code_file
GROUP BY repo_id, file_type
ORDER BY repo_id, cnt DESC;

-- 5) code_symbol summary
SELECT repo_id, symbol_type, COUNT(*) AS cnt
FROM code_symbol
GROUP BY repo_id, symbol_type
ORDER BY repo_id, symbol_type;

-- 6) code_dependency summary
SELECT repo_id, COUNT(*) AS dependency_cnt
FROM code_dependency
GROUP BY repo_id
ORDER BY repo_id;

-- 7) code_chunk summary
SELECT repo_id, chunk_type, vector_status, COUNT(*) AS cnt
FROM code_chunk
GROUP BY repo_id, chunk_type, vector_status
ORDER BY repo_id, chunk_type, vector_status;

-- 8) latest tool call logs
SELECT id, user_id, repo_id, tool_name, success, cost_ms, LEFT(error_msg, 120) AS error_preview, created_at
FROM tool_call_log
ORDER BY id DESC
LIMIT 20;

-- 9) latest llm call logs
SELECT id, user_id, repo_id, model_name, success, token_input, token_output, cost_ms, error_code, created_at
FROM llm_call_log
ORDER BY id DESC
LIMIT 20;

-- 10) chat history check
SELECT id, session_id, role, LEFT(content, 120) AS content_preview, created_at
FROM chat_message
ORDER BY id DESC
LIMIT 20;
