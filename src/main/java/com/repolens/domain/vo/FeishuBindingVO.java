package com.repolens.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Feishu binding view object — safe for API responses.
 *
 * <p>Deliberately excludes {@code appSecret} and {@code appSecretEnc}
 * so that encrypted secrets are never leaked to the client.
 */
@Data
public class FeishuBindingVO {

    private Long id;

    private Long repoId;

    private Long sessionId;

    private String botName;

    private String appId;

    /** Connection status: CONNECTED / DISCONNECTED / ERROR */
    private String status;

    /** Last error message (nullable) */
    private String lastError;

    private LocalDateTime createdAt;
}
