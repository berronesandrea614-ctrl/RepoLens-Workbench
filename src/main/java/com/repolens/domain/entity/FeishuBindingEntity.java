package com.repolens.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Feishu binding entity.
 *
 * <p>Stores the binding between a Feishu bot and a RepoLens project (repo).
 * The App Secret is stored encrypted (AES-256-GCM); never stored in plaintext.
 */
@Data
@TableName("feishu_binding")
public class FeishuBindingEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** User ID who created this binding */
    private Long userId;

    /** Bound RepoLens project (repo) */
    private Long repoId;

    /** Associated chat session ID (nullable) */
    private Long sessionId;

    /** Display name for the bot */
    private String botName;

    /** Feishu App ID */
    private String appId;

    /** AES-256-GCM encrypted App Secret (base64, never stored in plaintext) */
    private String appSecretEnc;

    /** Connection status: CONNECTED / DISCONNECTED / ERROR */
    private String status;

    /** Last error message (nullable) */
    private String lastError;

    /** 固定的 Claude 会话 UUID：一机器人一会话，飞书每条消息 --resume 接着它跑（有记忆、跨重启不变）。 */
    private String claudeSessionId;

    /** 会话是否已创建过：0=首次用 --session-id 建，1=之后用 --resume 续。 */
    private Integer sessionStarted;

    /** Creation timestamp */
    private LocalDateTime createdAt;

    /** Update timestamp */
    private LocalDateTime updatedAt;
}
