package com.repolens.service.impl;

import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.AppSettingEntity;
import com.repolens.domain.entity.EgressLogEntity;
import com.repolens.mapper.AppSettingMapper;
import com.repolens.mapper.EgressLogMapper;
import com.repolens.service.EgressPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 应用层出网策略实现（Feature G）。
 *
 * <p>IP 分类复用 SsrfGuard 的判断逻辑（相同的 InetAddress 判断条件），
 * 不重复造轮子，仅抽取 is_loopback 判定（非内网即外网、仅回环才本地）。
 *
 * <p>egress_log 写入失败安全：任何 DB 异常都静默吞掉，绝不阻断主流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EgressPolicyImpl implements EgressPolicy {

    /** app_setting key：隐私模式（LOCAL_ONLY / ALLOWLIST / OPEN）。 */
    public static final String KEY_PRIVACY_MODE = "privacy.mode";
    /** app_setting key：白名单主机列表（逗号分隔）。 */
    public static final String KEY_PRIVACY_ALLOWLIST = "privacy.allowlist";

    /** 模式默认值：OPEN（向后兼容，存量部署不改变行为）。 */
    private static final String DEFAULT_MODE = EgressLogEntity.MODE_OPEN;

    private final AppSettingMapper appSettingMapper;
    private final EgressLogMapper egressLogMapper;

    // ─── EgressPolicy interface ───────────────────────────────────────────────

    @Override
    public void checkAndLog(String host, int port, String purpose, String modelName) {
        String mode = getMode();
        String allowlist = readSetting(KEY_PRIVACY_ALLOWLIST);

        // 解析 IP（失败时按"不可信外网"处理）
        ResolvedHost resolved = resolveHost(host);

        // 根据模式判断是否放行
        boolean allowed = decide(mode, resolved.isLoopback, host, allowlist);

        // 写审计日志（失败安全）
        persistSafe(host, port, purpose, modelName, mode, resolved, allowed);

        // 不放行则阻断
        if (!allowed) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "Egress blocked by " + mode + " mode: " + host
                            + " — 应用层隐私网关已拦截出站连接。"
                            + "本网关仅覆盖经本应用发起的连接，完整零出网证明请配合 JFR/OS 层交叉验证。");
        }
    }

    @Override
    public String getMode() {
        String v = readSetting(KEY_PRIVACY_MODE);
        if (!StringUtils.hasText(v)) {
            return DEFAULT_MODE;
        }
        String upper = v.trim().toUpperCase();
        return switch (upper) {
            case EgressLogEntity.MODE_LOCAL_ONLY,
                 EgressLogEntity.MODE_ALLOWLIST,
                 EgressLogEntity.MODE_OPEN -> upper;
            default -> DEFAULT_MODE;
        };
    }

    // ─── Pure decision logic (testable without Spring context) ───────────────

    /**
     * 决策逻辑（纯函数，无 I/O，便于单测）。
     *
     * @param mode       当前隐私模式（LOCAL_ONLY / ALLOWLIST / OPEN）
     * @param isLoopback 目标地址是否为纯回环
     * @param host       目标主机名（原始，用于白名单比对）
     * @param allowlist  逗号分隔的白名单（可为 null/空）
     * @return true=放行，false=拦截
     */
    public static boolean decide(String mode, boolean isLoopback, String host, String allowlist) {
        if (EgressLogEntity.MODE_LOCAL_ONLY.equals(mode)) {
            return isLoopback;
        }
        if (EgressLogEntity.MODE_ALLOWLIST.equals(mode)) {
            return isLoopback || isInAllowlist(host, allowlist);
        }
        // OPEN 或未知模式：全放行
        return true;
    }

    /**
     * 判断 host 是否在逗号分隔的白名单中（大小写不敏感，精确匹配整段）。
     * allowlist 为 null/空时返回 false。
     */
    public static boolean isInAllowlist(String host, String allowlist) {
        if (!StringUtils.hasText(host) || !StringUtils.hasText(allowlist)) {
            return false;
        }
        String normalized = host.trim().toLowerCase();
        Set<String> entries = Arrays.stream(allowlist.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        return entries.contains(normalized);
    }

    /**
     * 解析主机的实际 IP 列表，判断是否全部为回环地址。
     *
     * <p>复用 SsrfGuard 的判断逻辑：使用 InetAddress.isLoopbackAddress()。
     * DNS 解析失败时视为"非回环外网"（保守安全）。
     */
    public static ResolvedHost resolveHost(String host) {
        if (!StringUtils.hasText(host)) {
            return new ResolvedHost(null, false);
        }
        String normalized = stripBrackets(host.trim());
        try {
            InetAddress[] addresses = InetAddress.getAllByName(normalized);
            // 只要有任何一个 IP 不是回环，整体就不视为回环
            boolean allLoopback = addresses.length > 0
                    && Arrays.stream(addresses).allMatch(InetAddress::isLoopbackAddress);
            String firstIp = addresses.length > 0 ? addresses[0].getHostAddress() : null;
            return new ResolvedHost(firstIp, allLoopback);
        } catch (UnknownHostException ex) {
            // DNS 解析失败：视为不可信外网
            return new ResolvedHost(null, false);
        }
    }

    /** 简单容器：保存 DNS 解析后的第一个 IP 和 is_loopback 标志。 */
    public static final class ResolvedHost {
        public final String firstIp;
        public final boolean isLoopback;

        ResolvedHost(String firstIp, boolean isLoopback) {
            this.firstIp = firstIp;
            this.isLoopback = isLoopback;
        }
    }

    // ─── Internal helpers ─────────────────────────────────────────────────────

    private String readSetting(String key) {
        try {
            AppSettingEntity entity = appSettingMapper.selectById(key);
            return entity != null ? entity.getV() : null;
        } catch (Exception ex) {
            log.warn("EgressPolicy: failed to read app_setting key={}, err={}", key, ex.getMessage());
            return null;
        }
    }

    private void persistSafe(String host, int port, String purpose, String modelName,
                              String mode, ResolvedHost resolved, boolean allowed) {
        try {
            EgressLogEntity entity = new EgressLogEntity();
            entity.setTs(LocalDateTime.now());
            entity.setPurpose(purpose != null ? purpose : "UNKNOWN");
            entity.setDestHost(host != null ? host : "");
            entity.setDestPort(port > 0 ? port : null);
            entity.setResolvedIp(resolved.firstIp);
            entity.setIsLoopback(resolved.isLoopback);
            entity.setAllowed(allowed);
            entity.setPrivacyMode(mode);
            entity.setModelName(modelName);
            entity.setBytesOut(null);
            egressLogMapper.insert(entity);
        } catch (Exception ex) {
            log.warn("EgressPolicy: persist egress_log failed (failure-safe), host={}, err={}",
                    host, ex.getMessage());
        }
    }

    private static String stripBrackets(String host) {
        if (host.length() > 1 && host.charAt(0) == '[' && host.charAt(host.length() - 1) == ']') {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }
}
