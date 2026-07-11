package com.repolens.domain.dto;

import lombok.Data;

/**
 * PUT /api/privacy/mode 请求体（Feature G）。
 */
@Data
public class PrivacyModeRequest {

    /**
     * 目标隐私模式：LOCAL_ONLY / ALLOWLIST / OPEN。
     */
    private String mode;

    /**
     * 白名单主机列表（逗号分隔，仅 ALLOWLIST 模式有效，其他模式下忽略）。
     * 例如："api.deepseek.com,registry.npmjs.org"。
     */
    private String allowlist;
}
