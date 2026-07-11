package com.repolens.service.impl.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.domain.entity.DependencyCheckEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 包存在性 Registry 客户端。
 * 使用 JDK HttpClient 单例（同 SettingsServiceImpl 范式），零新依赖。
 * <p>
 * 仅发送包名到公共 registry（不含源代码/路径/上下文），符合 RepoLens 隐私卖点。
 * <ul>
 *   <li>npm   → GET https://registry.npmjs.org/{encodedName}（200=存在，404=不存在）</li>
 *   <li>PyPI  → GET https://pypi.org/pypi/{encodedName}/json（200=存在，404=不存在）</li>
 * </ul>
 * 超时 5 秒；网络异常 / 超时 / 其他状态码 → {@link DependencyCheckEntity#VERDICT_UNKNOWN}，绝不抛出。
 */
@Slf4j
@Component
public class RegistryClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static final String NPM_BASE    = "https://registry.npmjs.org/";
    private static final String PYPI_BASE   = "https://pypi.org/pypi/";
    private static final String MAVEN_SOLR  = "https://search.maven.org/solrsearch/select";
    private static final ObjectMapper MAVEN_JSON = new ObjectMapper();

    /**
     * 单例 HttpClient（同 SettingsServiceImpl 范式）。
     * connectTimeout 与请求超时保持一致，避免 selector 线程泄漏。
     */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();

    /**
     * 检查 npm 包是否存在。
     *
     * @param packageName npm 包名（可含 @ 前缀的 scoped 包）
     * @return OK / NOT_FOUND / UNKNOWN
     */
    public String checkNpm(String packageName) {
        if (packageName == null || packageName.isBlank()) return DependencyCheckEntity.VERDICT_UNKNOWN;
        try {
            // URL-encode：scoped 包 "@scope/name" 的 @ 和 / 都需要编码
            String encoded = encodePkgName(packageName);
            URI uri = URI.create(NPM_BASE + encoded);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<Void> resp = httpClient.send(req, HttpResponse.BodyHandlers.discarding());
            int status = resp.statusCode();
            if (status == 200) return DependencyCheckEntity.VERDICT_OK;
            if (status == 404) return DependencyCheckEntity.VERDICT_NOT_FOUND;
            log.debug("npm registry returned unexpected status {} for package {}", status, packageName);
            return DependencyCheckEntity.VERDICT_UNKNOWN;
        } catch (Exception ex) {
            log.debug("npm registry check failed for {}: {}", packageName, ex.getMessage());
            return DependencyCheckEntity.VERDICT_UNKNOWN;
        }
    }

    /**
     * 检查 PyPI 包是否存在。
     *
     * @param packageName PyPI 项目名（已由调用方做过 PEP 503 归一化）
     * @return OK / NOT_FOUND / UNKNOWN
     */
    public String checkPypi(String packageName) {
        if (packageName == null || packageName.isBlank()) return DependencyCheckEntity.VERDICT_UNKNOWN;
        try {
            String encoded = URLEncoder.encode(packageName, StandardCharsets.UTF_8);
            URI uri = URI.create(PYPI_BASE + encoded + "/json");
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<Void> resp = httpClient.send(req, HttpResponse.BodyHandlers.discarding());
            int status = resp.statusCode();
            if (status == 200) return DependencyCheckEntity.VERDICT_OK;
            if (status == 404) return DependencyCheckEntity.VERDICT_NOT_FOUND;
            log.debug("PyPI returned unexpected status {} for package {}", status, packageName);
            return DependencyCheckEntity.VERDICT_UNKNOWN;
        } catch (Exception ex) {
            log.debug("PyPI check failed for {}: {}", packageName, ex.getMessage());
            return DependencyCheckEntity.VERDICT_UNKNOWN;
        }
    }

    /**
     * 按 groupId:artifactId 坐标查询 Maven Central Solr（g:+AND+a: 查询）。
     *
     * @param groupId    Maven groupId
     * @param artifactId Maven artifactId
     * @return OK / NOT_FOUND / UNKNOWN
     */
    public String checkMaven(String groupId, String artifactId) {
        if (groupId == null || groupId.isBlank() || artifactId == null || artifactId.isBlank()) {
            return DependencyCheckEntity.VERDICT_UNKNOWN;
        }
        try {
            String q = "g:\"" + groupId + "\" AND a:\"" + artifactId + "\"";
            String url = MAVEN_SOLR + "?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8) + "&rows=1&wt=json";
            return queryMavenSolr(url);
        } catch (Exception ex) {
            log.debug("checkMaven failed for {}:{} - {}", groupId, artifactId, ex.getMessage());
            return DependencyCheckEntity.VERDICT_UNKNOWN;
        }
    }

    /**
     * 按全限定类名查询 Maven Central Solr（fc: 查询）。
     * 用于 Java import 级检测（无坐标时的备选判据）。
     *
     * @param fqn 全限定类名，如 com.google.common.collect.ImmutableList
     * @return OK / NOT_FOUND / UNKNOWN
     */
    public String checkMavenByClass(String fqn) {
        if (fqn == null || fqn.isBlank()) return DependencyCheckEntity.VERDICT_UNKNOWN;
        try {
            String q = "fc:\"" + fqn + "\"";
            String url = MAVEN_SOLR + "?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8) + "&rows=1&wt=json";
            return queryMavenSolr(url);
        } catch (Exception ex) {
            log.debug("checkMavenByClass failed for {} - {}", fqn, ex.getMessage());
            return DependencyCheckEntity.VERDICT_UNKNOWN;
        }
    }

    private String queryMavenSolr(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            log.debug("Maven Solr returned status {}", resp.statusCode());
            return DependencyCheckEntity.VERDICT_UNKNOWN;
        }
        return parseMavenSolrResponse(resp.body());
    }

    /**
     * 解析 Maven Central Solr JSON 响应，读取 response.numFound。
     * 包级私有供单测直接调用（纯函数）。
     */
    String parseMavenSolrResponse(String body) {
        if (body == null) return DependencyCheckEntity.VERDICT_UNKNOWN;
        try {
            JsonNode root = MAVEN_JSON.readTree(body);
            JsonNode numFound = root.path("response").path("numFound");
            if (numFound.isMissingNode()) return DependencyCheckEntity.VERDICT_UNKNOWN;
            return numFound.asInt(0) > 0
                    ? DependencyCheckEntity.VERDICT_OK
                    : DependencyCheckEntity.VERDICT_NOT_FOUND;
        } catch (Exception ex) {
            log.debug("Maven Solr parse failed: {}", ex.getMessage());
            return DependencyCheckEntity.VERDICT_UNKNOWN;
        }
    }

    /**
     * URL 编码包名：将 scoped npm 包名 "@scope/name" 的 @ 保留，
     * 但 / 必须编码成 %2F（否则 registry 路由会解析错误）。
     * 其余非 ASCII 字符也做完整编码。
     */
    private String encodePkgName(String name) {
        if (name.startsWith("@")) {
            // @scope/name → @scope%2Fname
            String noAt = name.substring(1);
            return "@" + URLEncoder.encode(noAt, StandardCharsets.UTF_8);
        }
        return URLEncoder.encode(name, StandardCharsets.UTF_8);
    }
}
