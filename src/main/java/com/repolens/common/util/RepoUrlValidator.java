package com.repolens.common.util;

import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/**
 * 仓库地址校验器。
 * 远端协议默认允许；本地 file:// 和 Windows 绝对路径只用于本地 demo / dev 场景，
 * 必须显式打开开关，并限制在白名单根目录内。生产环境建议关闭该能力。
 */
@Component
public class RepoUrlValidator {

    private static final Pattern HTTP_PATTERN = Pattern.compile("^https?://[^\\s]+$");
    private static final Pattern GIT_PATTERN = Pattern.compile("^git://[^\\s]+$");
    private static final Pattern SSH_PATTERN = Pattern.compile("^ssh://[^\\s]+$");
    private static final Pattern SSH_SHORT_PATTERN = Pattern.compile("^git@[^\\s:]+:[^\\s]+$");
    private static final Pattern FILE_PATTERN = Pattern.compile("^file:///.+$");
    private static final Pattern WINDOWS_ABS_PATH_PATTERN = Pattern.compile("^[a-zA-Z]:[\\\\/].+$");

    @Value("${repolens.repo.allow-local-file-url:true}")
    private boolean allowLocalFileUrl;

    /** SSRF 防护开关：拒绝远端仓库地址指向回环/私网/链路本地地址，默认开启。 */
    @Value("${repolens.repo.block-private-hosts:true}")
    private boolean blockPrivateHosts;

    @Value("${repolens.repo.allowed-local-repo-root:E:/shixi_xiangmu/RepoLens/test-repos}")
    private String allowedLocalRepoRoot;

    public void validate(String repoUrl) {
        if (!StringUtils.hasText(repoUrl)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "repoUrl is required");
        }
        String trimmed = repoUrl.trim();
        if (isRemoteRepoUrl(trimmed)) {
            // SSRF 防护：克隆是服务端发起的网络请求，必须拒绝指向内网/回环的地址，
            // 防止攻击者用仓库地址探测内网服务（如 http://127.0.0.1:6379）。
            if (blockPrivateHosts) {
                ensureHostNotPrivate(trimmed);
            }
            return;
        }
        if (isLocalRepoUrl(trimmed)) {
            validateLocalRepoUrl(trimmed);
            return;
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "Invalid repoUrl format");
    }

    public boolean isValid(String repoUrl) {
        try {
            validate(repoUrl);
            return true;
        } catch (BizException ex) {
            return false;
        }
    }

    /**
     * 拒绝指向回环/私网/链路本地的主机，防 SSRF。
     * 解析 host：http(s)://host/...、git://host/...、ssh://host/...、git@host:path。
     *
     * <p>关键：把 host 解析成实际 IP 后再分类（{@link SsrfGuard}），才能挡住
     * {@code http://2130706433/...}（十进制回环）与 {@code localtest.me}（DNS→127.0.0.1）
     * 这类绕过纯字符串黑名单的写法。
     */
    private void ensureHostNotPrivate(String repoUrl) {
        String host = extractHost(repoUrl);
        if (host == null || host.isBlank()) {
            // 提取不到 host（理论上远端白名单都能取到）：保守拒绝，避免留下绕过口子。
            throw new BizException(ErrorCode.BAD_REQUEST, "repoUrl host is not allowed");
        }
        try {
            SsrfGuard.assertHostAllowed(host);
        } catch (BizException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "repoUrl host is not allowed");
        }
    }

    private String extractHost(String repoUrl) {
        try {
            if (repoUrl.startsWith("git@")) {
                // git@host:owner/repo.git
                int at = repoUrl.indexOf('@');
                int colon = repoUrl.indexOf(':', at);
                return colon > at ? repoUrl.substring(at + 1, colon) : repoUrl.substring(at + 1);
            }
            return URI.create(repoUrl).getHost();
        } catch (Exception ex) {
            return null;
        }
    }

    private boolean isRemoteRepoUrl(String repoUrl) {
        return HTTP_PATTERN.matcher(repoUrl).matches()
                || GIT_PATTERN.matcher(repoUrl).matches()
                || SSH_PATTERN.matcher(repoUrl).matches()
                || SSH_SHORT_PATTERN.matcher(repoUrl).matches();
    }

    private boolean isLocalRepoUrl(String repoUrl) {
        return FILE_PATTERN.matcher(repoUrl).matches() || WINDOWS_ABS_PATH_PATTERN.matcher(repoUrl).matches();
    }

    /**
     * 本地仓库地址只用于本地演示。
     * 这里除了开关判断，还要把路径 normalize 后限制在 allowed root 内，避免被滥用读取服务器任意路径。
     */
    private void validateLocalRepoUrl(String repoUrl) {
        if (!allowLocalFileUrl) {
            throw new BizException(ErrorCode.BAD_REQUEST, "local file repo url is disabled");
        }
        Path allowedRoot = normalizeAllowedRoot();
        Path localPath = normalizeLocalRepoPath(repoUrl);
        if (!localPath.startsWith(allowedRoot)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "local repo path is outside allowed root");
        }
    }

    private Path normalizeAllowedRoot() {
        if (!StringUtils.hasText(allowedLocalRepoRoot)) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "allowed local repo root is not configured");
        }
        return Paths.get(allowedLocalRepoRoot.trim()).toAbsolutePath().normalize();
    }

    /**
     * 把本地 file:// url（或 Windows 绝对路径）解析成校验后的绝对 Path，供导入阶段复用。
     * 与 normalizeLocalRepoPath 共用同一套非 ASCII 容错逻辑。
     */
    public Path resolveLocalRepoPath(String repoUrl) {
        return normalizeLocalRepoPath(repoUrl);
    }

    private Path normalizeLocalRepoPath(String repoUrl) {
        try {
            if (FILE_PATTERN.matcher(repoUrl).matches()) {
                // 优先按标准 URI 解析（支持已百分号编码的路径）；
                // 非 ASCII（如中文目录）未编码时 URI.create 会抛异常，回退到手动剥离 scheme。
                try {
                    return Paths.get(URI.create(repoUrl)).toAbsolutePath().normalize();
                } catch (IllegalArgumentException encodedFail) {
                    String raw = repoUrl.substring(repoUrl.indexOf("://") + 3); // file:///Users/... -> /Users/...
                    return Paths.get(raw).toAbsolutePath().normalize();
                }
            }
            return Paths.get(repoUrl).toAbsolutePath().normalize();
        } catch (Exception ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Invalid local repo path");
        }
    }
}
