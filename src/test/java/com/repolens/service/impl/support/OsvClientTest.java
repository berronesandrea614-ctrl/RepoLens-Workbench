package com.repolens.service.impl.support;

import com.repolens.domain.entity.DependencyCheckEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OsvClient 单测 — 仅测试 parseResponse（纯函数，无 HTTP）。
 * queryBatch 的空输入快速路径也无 HTTP。
 */
class OsvClientTest {

    private final OsvClient client = new OsvClient();

    private ExtractedDep npm(String n) {
        return new ExtractedDep("npm", n, null, "MANIFEST", "package.json", null);
    }
    private ExtractedDep pypi(String n) {
        return new ExtractedDep("pypi", n, null, "MANIFEST", "req.txt", null);
    }

    @Test
    void MAL_id_returns_MALICIOUS() {
        String body = "{\"results\":[{\"vulns\":[{\"id\":\"MAL-2019-001\"}]}]}";
        Map<String, String> r = client.parseResponse(List.of(npm("event-stream")), body);
        assertThat(r).containsEntry("npm:event-stream", DependencyCheckEntity.VERDICT_MALICIOUS);
    }

    @Test
    void GHSA_id_returns_VULNERABLE() {
        String body = "{\"results\":[{\"vulns\":[{\"id\":\"GHSA-xxxx-yyyy-zzzz\"}]}]}";
        Map<String, String> r = client.parseResponse(List.of(pypi("requests")), body);
        assertThat(r).containsEntry("pypi:requests", DependencyCheckEntity.VERDICT_VULNERABLE);
    }

    @Test
    void CVE_id_returns_VULNERABLE() {
        String body = "{\"results\":[{\"vulns\":[{\"id\":\"CVE-2021-44228\"}]}]}";
        Map<String, String> r = client.parseResponse(List.of(npm("log4j-test")), body);
        assertThat(r).containsEntry("npm:log4j-test", DependencyCheckEntity.VERDICT_VULNERABLE);
    }

    @Test
    void MAL_wins_over_GHSA_in_same_package() {
        String body = "{\"results\":[{\"vulns\":[{\"id\":\"GHSA-bad\"},{\"id\":\"MAL-99\"}]}]}";
        Map<String, String> r = client.parseResponse(List.of(npm("evil")), body);
        assertThat(r).containsEntry("npm:evil", DependencyCheckEntity.VERDICT_MALICIOUS);
    }

    @Test
    void empty_vulns_returns_no_entry() {
        String body = "{\"results\":[{\"vulns\":[]}]}";
        assertThat(client.parseResponse(List.of(npm("lodash")), body)).doesNotContainKey("npm:lodash");
    }

    @Test
    void malformed_json_returns_empty_map() {
        assertThat(client.parseResponse(List.of(npm("x")), "NOT_JSON")).isEmpty();
    }

    @Test
    void empty_deps_returns_empty_without_http() {
        assertThat(client.queryBatch(List.of())).isEmpty();
    }

    @Test
    void null_deps_returns_empty_without_http() {
        assertThat(client.queryBatch(null)).isEmpty();
    }

    @Test
    void multi_dep_results_map_by_position() {
        String body = "{\"results\":[{\"vulns\":[]},{\"vulns\":[{\"id\":\"MAL-2\"}]}]}";
        List<ExtractedDep> deps = List.of(npm("safe-pkg"), pypi("evil-pkg"));
        Map<String, String> r = client.parseResponse(deps, body);
        assertThat(r).doesNotContainKey("npm:safe-pkg");
        assertThat(r).containsEntry("pypi:evil-pkg", DependencyCheckEntity.VERDICT_MALICIOUS);
    }

    @Test
    void missing_results_key_returns_empty() {
        String body = "{\"other\":\"stuff\"}";
        assertThat(client.parseResponse(List.of(npm("x")), body)).isEmpty();
    }
}
