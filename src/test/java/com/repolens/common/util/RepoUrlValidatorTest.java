package com.repolens.common.util;

import com.repolens.common.exception.BizException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.nio.file.Paths;

class RepoUrlValidatorTest {

    @Test
    void shouldAcceptNonAsciiLocalFileUrl(@TempDir Path tempRoot) {
        RepoUrlValidator validator = buildValidator(true, tempRoot.toString());
        // file:///.../个人项目/quwantongcheng —— 中文段未百分号编码，URI.create 会抛异常，走回退分支
        String url = "file://" + tempRoot.toString().replace('\\', '/') + "/个人项目/quwantongcheng";

        // 校验（allowed root 命中）不抛异常
        Assertions.assertDoesNotThrow(() -> validator.validate(url));

        // 解析出的绝对路径应等于真实路径
        Path expected = Paths.get(tempRoot.toString(), "个人项目", "quwantongcheng").toAbsolutePath().normalize();
        Assertions.assertEquals(expected, validator.resolveLocalRepoPath(url));
    }

    @Test
    void shouldAcceptRemoteFormatsAndAllowedLocalPaths(@TempDir Path tempRoot) {
        // 用临时目录作为 allowed root，保证测试跨平台（macOS / Linux / Windows / CI）都通过，
        // 不再依赖开发机上写死的 Windows 盘符路径。
        RepoUrlValidator validator = buildValidator(true, tempRoot.toString());
        Path localRepo = tempRoot.resolve("repolens-demo-service");

        Assertions.assertDoesNotThrow(() -> validator.validate("https://github.com/acme/repo.git"));
        Assertions.assertDoesNotThrow(() -> validator.validate("http://gitlab.local/acme/repo"));
        Assertions.assertDoesNotThrow(() -> validator.validate("git://example.com/acme/repo.git"));
        Assertions.assertDoesNotThrow(() -> validator.validate("ssh://git@example.com/acme/repo.git"));
        Assertions.assertDoesNotThrow(() -> validator.validate("git@example.com:acme/repo.git"));
        // allowed root 内的本地 file:// 仓库：用真实临时路径构造 file URL，跨平台可用。
        Assertions.assertDoesNotThrow(() -> validator.validate(localRepo.toUri().toString()));

        // Windows 盘符绝对路径形态只在 Windows 上有意义（其他系统 Paths.get 不解析盘符），
        // 因此仅在 Windows 下断言该形态，保留原有覆盖但不破坏其他平台。
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (isWindows) {
            RepoUrlValidator winValidator = buildValidator(true, "E:/shixi_xiangmu/RepoLens/test-repos");
            Assertions.assertDoesNotThrow(() -> winValidator.validate(
                    "file:///E:/shixi_xiangmu/RepoLens/test-repos/repolens-demo-service"));
            Assertions.assertDoesNotThrow(() -> winValidator.validate(
                    "E:/shixi_xiangmu/RepoLens/test-repos/repolens-demo-service"));
            Assertions.assertDoesNotThrow(() -> winValidator.validate(
                    "E:\\shixi_xiangmu\\RepoLens\\test-repos\\repolens-demo-service"));
        }
    }

    @Test
    void shouldRejectWhenLocalFileUrlDisabled() {
        RepoUrlValidator validator = buildValidator(false, "E:/shixi_xiangmu/RepoLens/test-repos");

        BizException ex = Assertions.assertThrows(
                BizException.class,
                () -> validator.validate("file:///E:/shixi_xiangmu/RepoLens/test-repos/repolens-demo-service")
        );

        Assertions.assertTrue(ex.getMessage().contains("disabled"));
    }

    @Test
    void shouldRejectLocalPathOutsideAllowedRoot() {
        RepoUrlValidator validator = buildValidator(true, "E:/shixi_xiangmu/RepoLens/test-repos");

        BizException ex1 = Assertions.assertThrows(
                BizException.class,
                () -> validator.validate("file:///E:/shixi_xiangmu/RepoLens/README.md")
        );
        BizException ex2 = Assertions.assertThrows(
                BizException.class,
                () -> validator.validate("C:/Windows")
        );

        Assertions.assertTrue(ex1.getMessage().contains("outside allowed root"));
        Assertions.assertTrue(ex2.getMessage().contains("outside allowed root"));
    }

    @Test
    void shouldBlockPrivateHostsForSsrf() {
        RepoUrlValidator validator = buildValidator(true, "E:/shixi_xiangmu/RepoLens/test-repos");
        ReflectionTestUtils.setField(validator, "blockPrivateHosts", true);

        // 公网主机放行（用数字字面量避免测试依赖真实 DNS）
        Assertions.assertDoesNotThrow(() -> validator.validate("http://8.8.8.8/acme/repo.git"));
        Assertions.assertDoesNotThrow(() -> validator.validate("git://1.1.1.1/acme/repo.git"));
        // 回环/私网/链路本地一律拒绝（防 SSRF）
        Assertions.assertThrows(BizException.class, () -> validator.validate("http://127.0.0.1:6379/x"));
        Assertions.assertThrows(BizException.class, () -> validator.validate("http://localhost:3306/db"));
        Assertions.assertThrows(BizException.class, () -> validator.validate("http://10.0.0.5/repo.git"));
        Assertions.assertThrows(BizException.class, () -> validator.validate("http://192.168.1.10/repo.git"));
        Assertions.assertThrows(BizException.class, () -> validator.validate("http://169.254.169.254/latest/meta-data"));
        Assertions.assertThrows(BizException.class, () -> validator.validate("http://172.16.0.1/repo.git"));
        Assertions.assertThrows(BizException.class, () -> validator.validate("git@10.1.2.3:acme/repo.git"));
        // 十进制编码的回环 2130706433 == 127.0.0.1：字符串黑名单会漏，解析成 IP 后必须拒绝
        Assertions.assertThrows(BizException.class, () -> validator.validate("http://2130706433:6379/x.git"));
        // 0.0.0.0 任意本地地址
        Assertions.assertThrows(BizException.class, () -> validator.validate("http://0.0.0.0:8080/x.git"));
    }

    @Test
    void shouldRejectInvalidFormats() {
        RepoUrlValidator validator = buildValidator(true, "E:/shixi_xiangmu/RepoLens/test-repos");

        Assertions.assertThrows(BizException.class, () -> validator.validate(""));
        Assertions.assertThrows(BizException.class, () -> validator.validate("ftp://example.com/repo.git"));
        Assertions.assertThrows(BizException.class, () -> validator.validate("just-text"));
    }

    private RepoUrlValidator buildValidator(boolean allowLocalFileUrl, String allowedRoot) {
        RepoUrlValidator validator = new RepoUrlValidator();
        ReflectionTestUtils.setField(validator, "allowLocalFileUrl", allowLocalFileUrl);
        ReflectionTestUtils.setField(validator, "allowedLocalRepoRoot", allowedRoot);
        return validator;
    }
}
