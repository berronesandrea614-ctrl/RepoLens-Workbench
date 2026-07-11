package com.repolens.service.impl.support;

import com.repolens.domain.entity.DependencyCheckEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RegistryClient Maven 纯函数单测 (parseMavenSolrResponse, blank-arg guards)。
 * 不发真实 HTTP 请求。
 */
class RegistryClientMavenTest {

    private final RegistryClient client = new RegistryClient();

    @Test
    void numFound_gt_0_returns_OK() {
        String body = "{\"response\":{\"numFound\":5,\"start\":0,\"docs\":[]}}";
        assertThat(client.parseMavenSolrResponse(body)).isEqualTo(DependencyCheckEntity.VERDICT_OK);
    }

    @Test
    void numFound_0_returns_NOT_FOUND() {
        String body = "{\"response\":{\"numFound\":0,\"start\":0,\"docs\":[]}}";
        assertThat(client.parseMavenSolrResponse(body)).isEqualTo(DependencyCheckEntity.VERDICT_NOT_FOUND);
    }

    @Test
    void malformed_json_returns_UNKNOWN() {
        assertThat(client.parseMavenSolrResponse("NOT JSON")).isEqualTo(DependencyCheckEntity.VERDICT_UNKNOWN);
    }

    @Test
    void missing_numFound_returns_UNKNOWN() {
        assertThat(client.parseMavenSolrResponse("{\"response\":{}}"))
                .isEqualTo(DependencyCheckEntity.VERDICT_UNKNOWN);
    }

    @Test
    void null_body_returns_UNKNOWN() {
        assertThat(client.parseMavenSolrResponse(null)).isEqualTo(DependencyCheckEntity.VERDICT_UNKNOWN);
    }

    @Test
    void checkMaven_blank_groupId_returns_UNKNOWN() {
        assertThat(client.checkMaven("", "guava")).isEqualTo(DependencyCheckEntity.VERDICT_UNKNOWN);
    }

    @Test
    void checkMaven_blank_artifactId_returns_UNKNOWN() {
        assertThat(client.checkMaven("com.google.guava", "")).isEqualTo(DependencyCheckEntity.VERDICT_UNKNOWN);
    }

    @Test
    void checkMavenByClass_blank_fqn_returns_UNKNOWN() {
        assertThat(client.checkMavenByClass("")).isEqualTo(DependencyCheckEntity.VERDICT_UNKNOWN);
    }

    @Test
    void checkMavenByClass_null_fqn_returns_UNKNOWN() {
        assertThat(client.checkMavenByClass(null)).isEqualTo(DependencyCheckEntity.VERDICT_UNKNOWN);
    }
}
