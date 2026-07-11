USE repolens;

INSERT INTO `user` (username, password_hash, status, created_at, updated_at)
VALUES ('demo_admin', '$2a$10$w5u12fC9IlmJ7v2YBtxsDOnJq8QbkD6MdlAUPQjM6N3N7eBfAm8HC', 'ACTIVE', NOW(), NOW())
ON DUPLICATE KEY UPDATE
    username      = VALUES(username),
    password_hash = VALUES(password_hash),
    status        = VALUES(status),
    updated_at    = NOW();

INSERT INTO workspace (id, name, owner_id, description, created_at, updated_at)
VALUES (1, 'Demo Workspace', (SELECT id FROM `user` WHERE username = 'demo_admin'), 'RepoLens first stage demo workspace', NOW(), NOW())
ON DUPLICATE KEY UPDATE
    name        = VALUES(name),
    owner_id    = VALUES(owner_id),
    description = VALUES(description),
    updated_at  = NOW();

INSERT INTO workspace_member (workspace_id, user_id, role, created_at, updated_at)
VALUES (1, (SELECT id FROM `user` WHERE username = 'demo_admin'), 'OWNER', NOW(), NOW())
ON DUPLICATE KEY UPDATE
    role       = VALUES(role),
    updated_at = NOW();
