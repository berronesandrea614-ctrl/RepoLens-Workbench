package com.repolens.common.util;

import com.repolens.common.exception.BizException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * SsrfGuard 单测：核心是 {@link SsrfGuard#isForbiddenAddress} 的地址分类，
 * 用 IP 字面量直接构造 InetAddress，避免测试依赖真实 DNS。
 */
class SsrfGuardTest {

    private InetAddress ip(String literal) throws UnknownHostException {
        // 纯数字/点分/冒号字面量：getByName 不触发 DNS 查询。
        return InetAddress.getByName(literal);
    }

    @Test
    void isForbiddenAddress_rejectsInternalRanges() throws Exception {
        // 回环 / 私网 / 链路本地 / 任意本地
        Assertions.assertTrue(SsrfGuard.isForbiddenAddress(ip("127.0.0.1")));
        Assertions.assertTrue(SsrfGuard.isForbiddenAddress(ip("10.0.0.1")));
        Assertions.assertTrue(SsrfGuard.isForbiddenAddress(ip("192.168.1.1")));
        Assertions.assertTrue(SsrfGuard.isForbiddenAddress(ip("172.16.0.1")));
        Assertions.assertTrue(SsrfGuard.isForbiddenAddress(ip("169.254.1.1")));
        Assertions.assertTrue(SsrfGuard.isForbiddenAddress(ip("0.0.0.0")));
        // IPv6 回环 / 链路本地 / 唯一本地(fc00::/7) / IPv4-mapped 回环
        Assertions.assertTrue(SsrfGuard.isForbiddenAddress(ip("::1")));
        Assertions.assertTrue(SsrfGuard.isForbiddenAddress(ip("fe80::1")));
        Assertions.assertTrue(SsrfGuard.isForbiddenAddress(ip("fc00::1")));
        Assertions.assertTrue(SsrfGuard.isForbiddenAddress(ip("fd12:3456::1")));
        Assertions.assertTrue(SsrfGuard.isForbiddenAddress(ip("::ffff:127.0.0.1")));
    }

    @Test
    void isForbiddenAddress_allowsPublic() throws Exception {
        Assertions.assertFalse(SsrfGuard.isForbiddenAddress(ip("8.8.8.8")));
        Assertions.assertFalse(SsrfGuard.isForbiddenAddress(ip("1.1.1.1")));
    }

    @Test
    void assertHostAllowed_rejectsDecimalEncodedLoopback() {
        // 2130706433 == 127.0.0.1：getAllByName 会解析成回环 → 拒绝
        Assertions.assertThrows(BizException.class, () -> SsrfGuard.assertHostAllowed("2130706433"));
    }

    @Test
    void assertHostAllowed_rejectsLiteralLoopbackAndBlankAndUnknown() {
        Assertions.assertThrows(BizException.class, () -> SsrfGuard.assertHostAllowed("127.0.0.1"));
        Assertions.assertThrows(BizException.class, () -> SsrfGuard.assertHostAllowed("[::1]"));
        Assertions.assertThrows(BizException.class, () -> SsrfGuard.assertHostAllowed(""));
        Assertions.assertThrows(BizException.class, () -> SsrfGuard.assertHostAllowed(null));
        // 不可解析主机同样拒绝（避免放行不可控目标）
        Assertions.assertThrows(BizException.class,
                () -> SsrfGuard.assertHostAllowed("no-such-host.invalid"));
    }

    @Test
    void assertHostAllowed_allowsPublicLiteral() {
        Assertions.assertDoesNotThrow(() -> SsrfGuard.assertHostAllowed("8.8.8.8"));
    }

    @Test
    void assertHostAllowed_allowLoopbackFlagOnlyRelaxesLoopback() {
        // allowLoopback=true 时回环放行（ollama/local 场景）
        Assertions.assertDoesNotThrow(() -> SsrfGuard.assertHostAllowed("127.0.0.1", true));
        Assertions.assertDoesNotThrow(() -> SsrfGuard.assertHostAllowed("[::1]", true));
        // 但私网/链路本地即便 allowLoopback 也仍然拒绝
        Assertions.assertThrows(BizException.class, () -> SsrfGuard.assertHostAllowed("10.0.0.1", true));
        Assertions.assertThrows(BizException.class, () -> SsrfGuard.assertHostAllowed("169.254.1.1", true));
    }
}
