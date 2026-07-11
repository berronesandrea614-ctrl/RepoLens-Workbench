package com.repolens.domain.dto;

import lombok.Data;

/**
 * 运行时 LLM 设置的更新/测试请求体。
 * apiKey 留空/为 null 表示【保留现有 key】（脱敏回显后不误清空）；timeoutMs 为空表示不改。
 */
@Data
public class LlmSettingsUpdateRequest {

    private String provider;

    private String baseUrl;

    private String apiKey;

    private String modelName;

    private Integer timeoutMs;
}
