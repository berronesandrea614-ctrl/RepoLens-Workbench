-- =====================================================================
-- 重写内核 · M5：TodoWrite 反漂移 + 只读子代理(Task) + 会话 Checkpoint 回滚
-- 分支：rewrite/kernel-from-scratch
-- 单一事实源：与 db/init/04_rewrite_kernel_M1.sql 中的 rk_checkpoint 一一对应；
--            H2 等价 DDL 见 src/test/resources/db/rk-schema-h2.sql。
-- 说明：
--   - TodoWrite（反漂移清单）与 Task（只读子代理）为纯内存/运行期能力，不新增表；
--   - 仅 Checkpoint 需要持久化 → 本迁移只建 rk_checkpoint。
-- 避坑：不内联影子区全文——只记 shadow_snapshot_ref（快照落影子区 .rk/checkpoints/<id>/），
--       transcript_json 存打点对话，rewind 时按此还原影子区代码 + 截断对话。
-- =====================================================================
USE repolens;

CREATE TABLE IF NOT EXISTS rk_checkpoint (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    repo_id             BIGINT       NOT NULL,
    session_id          BIGINT       NOT NULL,
    run_id              BIGINT       NULL,
    shadow_id           BIGINT       NULL,
    label               VARCHAR(128) NULL,
    shadow_snapshot_ref VARCHAR(512) NULL COMMENT '影子区快照目录（相对影子根）',
    transcript_json     MEDIUMTEXT   NULL COMMENT '打点时的对话 JSON',
    step_index          INT          NOT NULL DEFAULT 0 COMMENT '打点时的对话步序',
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_rk_ckpt_session (repo_id, session_id),
    INDEX idx_rk_ckpt_shadow (shadow_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
