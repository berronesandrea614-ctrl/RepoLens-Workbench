package com.repolens.service.impl.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.repolens.domain.entity.DependencyCheckEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OSV.dev 批量恶意/漏洞检测客户端。
 * <p>
 * 一次 {@code POST https://api.osv.dev/v1/querybatch} 覆盖本次所有新依赖，省限流。
 * 返回 ids：{@code MAL-*} → MALICIOUS（slopsquat 命中）；{@code CVE-/GHSA-*} → VULNERABLE；
 * 空 → 不加入结果（调用方保留现有 verdict）。
 * </p>
 * <p>
 * 失败安全：任何异常（网络超时/5xx/JSON 解析）→ 返回空 Map，不改变调用方的任何已判定 verdict。
 * </p>
 */
@Slf4j
@Component
public class OsvClient {

    private static final String OSV_URL = "https://api.osv.dev/v1/querybatch";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** JDK HttpClient 单例，复用 selector 线程（同 SettingsServiceImpl/RegistryClient 范式）。 */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();

    /**
     * 批量查询 OSV 恶意/漏洞数据库。
     *
     * @param deps 需要检测的依赖列表
     * @return Map<"ecosystem:packageName", verdict>，verdict ∈ MALICIOUS / VULNERABLE。
     *         不在 map 中的依赖表示 OSV 无记录（不更新 verdict）。
     */
    public Map<String, String> queryBatch(List<ExtractedDep> deps) {
        if (deps == null || deps.isEmpty()) return Map.of();
        try {
            String body = buildRequest(deps);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(OSV_URL))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.debug("OSV querybatch returned status {}", resp.statusCode());
                return Map.of();
            }
            return parseResponse(deps, resp.body());
        } catch (Exception ex) {
            log.debug("OSV queryBatch failed: {}", ex.getMessage());
            return Map.of();
        }
    }

    private String buildRequest(List<ExtractedDep> deps) throws Exception {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode queries = root.putArray("queries");
        for (ExtractedDep dep : deps) {
            ObjectNode pkg = queries.addObject().putObject("package");
            pkg.put("name", dep.name());
            pkg.put("ecosystem", toOsvEcosystem(dep.ecosystem()));
        }
        return JSON.writeValueAsString(root);
    }

    /**
     * 解析 OSV querybatch 响应 — 包级私有供单测直接调用（纯函数，无副作用）。
     */
    Map<String, String> parseResponse(List<ExtractedDep> deps, String body) {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode results = root.get("results");
            if (results == null || !results.isArray()) return result;
            for (int i = 0; i < deps.size() && i < results.size(); i++) {
                JsonNode vulns = results.get(i).get("vulns");
                if (vulns == null || !vulns.isArray() || vulns.isEmpty()) continue;
                String key = deps.get(i).ecosystem() + ":" + deps.get(i).name();
                boolean mal = false, vuln = false;
                for (JsonNode v : vulns) {
                    String id = v.path("id").asText("");
                    if (id.startsWith("MAL-")) mal = true;
                    else if (id.startsWith("CVE-") || id.startsWith("GHSA-")) vuln = true;
                }
                result.put(key, mal
                        ? DependencyCheckEntity.VERDICT_MALICIOUS
                        : DependencyCheckEntity.VERDICT_VULNERABLE);
            }
        } catch (Exception ex) {
            log.debug("OSV parse failed: {}", ex.getMessage());
        }
        return result;
    }

    private String toOsvEcosystem(String eco) {
        return switch (eco) {
            case ExtractedDep.ECOSYSTEM_NPM   -> "npm";
            case ExtractedDep.ECOSYSTEM_PYPI  -> "PyPI";
            case ExtractedDep.ECOSYSTEM_MAVEN -> "Maven";
            default -> eco;
        };
    }
}
