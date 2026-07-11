package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 运行时 LLM 设置的对外视图。
 * 安全：只返回脱敏后的 apiKeyMasked（"" 或 "****last4"），永不回传完整 api-key。
 */
@Data
@Builder
public class LlmSettingsVO {

    private String provider;

    private String baseUrl;

    private String modelName;

    private int timeoutMs;

    /** 脱敏 api-key：空则 ""，否则 "****" + 末 4 位。 */
    private String apiKeyMasked;
}
