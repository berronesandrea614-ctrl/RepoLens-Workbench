package com.repolens.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 需求意图可视化（insight）模块可配置参数。
 *
 * <p>通过 application.yml 中 repolens.insight.* 配置，支持：
 * <ul>
 *   <li>sensitivePatterns：敏感区命中规则（glob 风格，大小写不敏感），命中触发 kind=risk。</li>
 * </ul>
 *
 * <p>空列表 = 关闭敏感区规则（不标注任何风险步骤）。
 *
 * <p>默认敏感模式：*Security*, *Auth*, *Payment*, delete*, *Config, *Migration*。
 */
@Data
@Component
@ConfigurationProperties(prefix = "repolens.insight")
public class InsightProperties {

    /**
     * 敏感区命中规则列表（glob 风格，匹配文件名或类名，大小写不敏感）。
     * 命中任一规则 → 步骤 kind=risk，节点 cls=danger。
     * 空列表 = 关闭敏感区规则。
     */
    private List<String> sensitivePatterns = List.of(
            "*Security*",
            "*Auth*",
            "*Payment*",
            "delete*",
            "*Config",
            "*Migration*"
    );
}
