package com.repolens.common.util;

import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * SSRF 防护工具：把主机名解析成实际 IP 后做地址分类，拒绝指向回环/私网/链路本地/组播等
 * 内部地址的出站请求。
 *
 * <p>相比纯字符串前缀黑名单，这里 {@link InetAddress#getAllByName(String)} 覆盖了：
 * <ul>
 *   <li>十进制/十六进制/八进制等 IPv4 编码变体（如 2130706433 == 127.0.0.1）；</li>
 *   <li>解析到内网的域名（DNS A/AAAA，如 localtest.me → 127.0.0.1），
 *       在校验时刻即拒绝，缓解 DNS rebinding。</li>
 * </ul>
 * 解析失败（UnknownHostException）同样拒绝，避免把不可控主机放行。
 */
public final class SsrfGuard {

    private SsrfGuard() {
    }

    /** 默认不允许回环/私网等内部地址。 */
    public static void assertHostAllowed(String host) {
        assertHostAllowed(host, false);
    }

    /**
     * 解析 host 的所有 IP，若任一地址属于内部地址段则拒绝。
     *
     * @param allowLoopback 为 true 时，仅额外放行【回环】地址（用于 ollama/local 等确实指向本机的场景）；
     *                      私网/链路本地/组播等仍然拒绝。
     */
    public static void assertHostAllowed(String host, boolean allowLoopback) {
        if (host == null || host.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "host is required");
        }
        String normalized = stripBrackets(host.trim());
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(normalized);
        } catch (UnknownHostException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "host is not allowed");
        }
        for (InetAddress address : addresses) {
            if (isForbiddenAddress(address)) {
                if (allowLoopback && address.isLoopbackAddress()) {
                    continue;
                }
                throw new BizException(ErrorCode.BAD_REQUEST, "host is not allowed");
            }
        }
    }

    /** 判断单个地址是否属于禁止访问的内部地址段（包 private 便于直接单测，不做 DNS）。 */
    static boolean isForbiddenAddress(InetAddress address) {
        if (address == null) {
            return true;
        }
        if (address.isLoopbackAddress()
                || address.isSiteLocalAddress()
                || address.isLinkLocalAddress()
                || address.isAnyLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes != null && bytes.length == 16) {
            // IPv6 unique-local fc00::/7（isSiteLocalAddress 在 JDK 里不覆盖 ULA）。
            if ((bytes[0] & 0xFE) == 0xFC) {
                return true;
            }
            // IPv4-mapped IPv6 ::ffff:a.b.c.d —— 取出内嵌 v4 再判一次（JDK 通常已归一，双保险）。
            if (isIpv4Mapped(bytes)) {
                try {
                    InetAddress embedded = InetAddress.getByAddress(
                            new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]});
                    return isForbiddenAddress(embedded);
                } catch (UnknownHostException ignore) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF;
    }

    private static String stripBrackets(String host) {
        if (host.length() > 1 && host.charAt(0) == '[' && host.charAt(host.length() - 1) == ']') {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }
}
