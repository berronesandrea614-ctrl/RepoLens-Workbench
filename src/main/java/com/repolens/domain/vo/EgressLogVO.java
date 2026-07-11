package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 出网审计日志 VO（Feature G）。供出网监控面板使用。
 */
@Data
@Builder
public class EgressLogVO {

    private Long id;

    /** 记录时间戳。 */
    private LocalDateTime ts;

    /** 出网用途（LLM / EMBEDDING / GIT_CLONE / DEP_CHECK）。 */
    private String purpose;

    /** 目标主机名。 */
    private String destHost;

    /** 目标端口（可为 null）。 */
    private Integer destPort;

    /** DNS 解析得到的第一个 IP（仅供审计）。 */
    private String resolvedIp;

    /** 是否回环地址。 */
    private boolean loopback;

    /**
     * 是否允许出网：false 表示被策略拦截（前端标红"已拦截"）。
     */
    private boolean allowed;

    /** 记录时的隐私模式快照。 */
    private String privacyMode;

    /** 关联模型名称（LLM/EMBEDDING 场景）。 */
    private String modelName;

    /** 发送字节数（MVP 阶段为 null）。 */
    private Long bytesOut;
}
