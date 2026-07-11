-- 飞书机器人 ↔ 持久 Claude 会话强绑定：
-- 每个 binding 固定一个 claude_session_id(UUID)，飞书每条消息都 `claude -p --resume <uuid>`
-- 接着同一个会话跑，有连续记忆、跨重启不变、输出干净（headless 无 TUI 刷屏）。
ALTER TABLE feishu_binding ADD COLUMN claude_session_id VARCHAR(64) NULL COMMENT '固定的 Claude 会话 UUID，一机器人一会话';
ALTER TABLE feishu_binding ADD COLUMN session_started TINYINT(1) NOT NULL DEFAULT 0 COMMENT '会话是否已用 --session-id 创建过(0=首次用--session-id建,1=之后用--resume续)';
