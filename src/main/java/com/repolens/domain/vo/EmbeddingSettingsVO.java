package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

/**
 * Embedding 配置的只读视图。
 * 只用于让 UI 呈现事实：当前用的是真实语义向量还是 mock 伪随机向量。
 * 不含 api-key，也不提供任何写入口（embedding 切换需改环境变量并重启，见 application.yml 注释）。
 */
@Data
@Builder
public class EmbeddingSettingsVO {

    /** 归一化后的 provider：mock / openai-compatible。 */
    private String provider;

    private String modelName;

    private int dimension;

    /** true = mock 伪随机向量：语义检索无效，仅关键词召回可用。 */
    private boolean mock;
}
