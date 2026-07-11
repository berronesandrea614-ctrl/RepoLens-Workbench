package com.repolens.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 出网审计日志（Feature G 代码0字节出网可验证）。
 *
 * <p>每次应用层向外发起网络连接时写入一条记录，allowed=0 表示被隐私策略拦截。
 * 写入失败一律失败安全（不阻断主流程）。
 */
@Data
@TableName("egress_log")
public class EgressLogEntity {

    /** 出网用途：LLM 对话调用。 */
    public static final String PURPOSE_LLM = "LLM";
    /** 出网用途：向量 Embedding 调用。 */
    public static final String PURPOSE_EMBEDDING = "EMBEDDING";
    /** 出网用途：Git 远端克隆。 */
    public static final String PURPOSE_GIT_CLONE = "GIT_CLONE";
    /** 出网用途：依赖体检 registry/OSV 查询。 */
    public static final String PURPOSE_DEP_CHECK = "DEP_CHECK";

    /** 隐私模式：本地仅限，非回环出网一律拦截。 */
    public static final String MODE_LOCAL_ONLY = "LOCAL_ONLY";
    /** 隐私模式：白名单，仅放行白名单或回环。 */
    public static final String MODE_ALLOWLIST = "ALLOWLIST";
    /** 隐私模式：开放，全放行但仍记录。 */
    public static final String MODE_OPEN = "OPEN";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 记录时间戳。 */
    private LocalDateTime ts;

    /** 出网用途：LLM / EMBEDDING / GIT_CLONE / DEP_CHECK 等。 */
    private String purpose;

    /** 目标主机名（未解析，原始输入）。 */
    private String destHost;

    /** 目标端口，可为 null（URL 中未显式指定时）。 */
    private Integer destPort;

    /** DNS 解析后的第一个 IP（仅供审计，不影响拦截逻辑）。 */
    private String resolvedIp;

    /** 目标地址是否为回环（127.x / ::1）。 */
    private Boolean isLoopback;

    /** 是否允许：0=被拦截，1=放行。 */
    private Boolean allowed;

    /** 记录时的隐私模式快照（LOCAL_ONLY / ALLOWLIST / OPEN）。 */
    private String privacyMode;

    /** 关联的模型名称（LLM/EMBEDDING 时有值，其他场景为 null）。 */
    private String modelName;

    /** 本次连接发送的字节数（当前仅供未来扩展，MVP 阶段为 null）。 */
    private Long bytesOut;
}
