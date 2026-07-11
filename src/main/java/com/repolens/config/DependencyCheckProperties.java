package com.repolens.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 依赖体检配置（前缀 repolens.depcheck）。
 * <p>
 * 在 application.yml 中：
 * <pre>
 * repolens:
 *   depcheck:
 *     mode: ${REPOLENS_DEPCHECK_MODE:ONLINE}
 *     cache-ttl-days: ${REPOLENS_DEPCHECK_CACHE_TTL_DAYS:7}
 * </pre>
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "repolens.depcheck")
public class DependencyCheckProperties {

    public enum Mode {
        /** 联网：registry 存在性 + OSV 恶意/漏洞检测，结果缓存。 */
        ONLINE,
        /** 离线：仅本地 typosquat + 内置 known-malicious 快照，零网络。隐私场景。 */
        OFFLINE
    }

    /**
     * 体检模式。默认 ONLINE。
     * 可通过环境变量 REPOLENS_DEPCHECK_MODE=OFFLINE 切换。
     */
    private Mode mode = Mode.ONLINE;

    /**
     * Registry 缓存 TTL（天），默认 7 天。
     * 超过 TTL 的缓存条目视为过期，重新联网检查。
     */
    private int cacheTtlDays = 7;

    /**
     * Java 源码 import 过滤：属于同一项目基包的 import 不视为外部依赖。
     * 空字符串表示不额外过滤（仍过滤 JDK 内置 java./javax./jdk./sun.）。
     * 可通过 REPOLENS_JAVA_BASE_PACKAGE 环境变量覆盖。
     */
    private String javaBasePackage = "";
}
