package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 隐私模式状态 VO（Feature G）。供前端常驻徽标使用。
 */
@Data
@Builder
public class PrivacyStatusVO {

    /** 当前隐私模式：LOCAL_ONLY / ALLOWLIST / OPEN。 */
    private String mode;

    /** 累计出网记录总数（仅含本应用发起的连接）。 */
    private long totalCount;

    /** 被拦截记录数（allowed=0）。 */
    private long blockedCount;

    /** 放行记录数（allowed=1）。 */
    private long allowedCount;

    /** 当前白名单（ALLOWLIST 模式下生效；逗号分隔的主机名，可为 null）。 */
    private String allowlist;
}
