package com.repolens.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.common.exception.BizException;
import com.repolens.domain.dto.LlmSettingsUpdateRequest;
import com.repolens.domain.vo.LlmTestResultVO;
import com.repolens.llm.config.LlmRuntimeConfig;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SettingsServiceImpl 安全单测：
 * - 连接测试对回环/私网/十进制编码回环做 SSRF 拒绝（不发起端口探测）；
 * - 失败/拒绝都只回通用文案，不回显上游精确状态码；
 * - 保存 base-url 时同样拒绝私网，但 ollama/local 放行回环。
 */
class SettingsServiceImplTest {

    private static final String FAIL_MESSAGE = "连接失败或地址不被允许";

    private LlmRuntimeConfig llmRuntimeConfig;
    private SettingsServiceImpl service;

    @BeforeEach
    void setup() {
        llmRuntimeConfig = mock(LlmRuntimeConfig.class);
        service = new SettingsServiceImpl(llmRuntimeConfig, new ObjectMapper());
    }

    private LlmSettingsUpdateRequest req(String provider, String baseUrl) {
        LlmSettingsUpdateRequest r = new LlmSettingsUpdateRequest();
        r.setProvider(provider);
        r.setBaseUrl(baseUrl);
        r.setModelName("some-model");
        r.setApiKey("sk-secret-1234");
        return r;
    }

    @Test
    void testLlm_mockProvider_returnsOkWithoutNetwork() {
        LlmTestResultVO vo = service.testLlm(req("mock", null));
        Assertions.assertTrue(vo.isOk());
    }

    @Test
    void testLlm_rejectsLoopbackBaseUrl_genericMessage() {
        LlmTestResultVO vo = service.testLlm(req("openai-compatible", "http://127.0.0.1:9091/v1"));
        Assertions.assertFalse(vo.isOk());
        Assertions.assertEquals(FAIL_MESSAGE, vo.getMessage());
    }

    @Test
    void testLlm_rejectsPrivateBaseUrl_genericMessage() {
        LlmTestResultVO vo = service.testLlm(req("openai-compatible", "http://192.168.1.10:8080/v1"));
        Assertions.assertFalse(vo.isOk());
        Assertions.assertEquals(FAIL_MESSAGE, vo.getMessage());
    }

    @Test
    void testLlm_rejectsDecimalEncodedLoopback_genericMessage() {
        // 2130706433 == 127.0.0.1，字符串黑名单会漏，解析后必须拒绝
        LlmTestResultVO vo = service.testLlm(req("openai-compatible", "http://2130706433:9091/v1"));
        Assertions.assertFalse(vo.isOk());
        Assertions.assertEquals(FAIL_MESSAGE, vo.getMessage());
    }

    @Test
    void updateLlm_rejectsPrivateBaseUrlForNonLocalProvider() {
        Assertions.assertThrows(BizException.class,
                () -> service.updateLlm(req("openai-compatible", "http://127.0.0.1:8080/v1")));
        verify(llmRuntimeConfig, never()).update(any(), any(), any(), any(), any());
    }

    @Test
    void updateLlm_rejectsDecimalLoopbackForNonLocalProvider() {
        Assertions.assertThrows(BizException.class,
                () -> service.updateLlm(req("openai-compatible", "http://2130706433:8080/v1")));
        verify(llmRuntimeConfig, never()).update(any(), any(), any(), any(), any());
    }

    @Test
    void updateLlm_allowsLoopbackForOllama() {
        LlmSettingsUpdateRequest r = req("ollama", "http://127.0.0.1:11434/v1");
        Assertions.assertDoesNotThrow(() -> service.updateLlm(r));
        verify(llmRuntimeConfig).update(eq("ollama"), eq("http://127.0.0.1:11434/v1"),
                eq("sk-secret-1234"), eq("some-model"), any());
    }

    /**
     * Regression guard (commit e4de11b): when the test-connection request carries a blank/empty
     * apiKey, the service must fall back to the stored key from llmRuntimeConfig.getApiKey()
     * instead of forwarding an empty key.  Before the fix a blank key was passed straight to
     * doTestCall(), causing a spurious 401 whenever the user clicked "Test" without re-entering
     * their key on the settings page.
     *
     * We use provider="mock" so no HTTP call is made, letting us assert solely that the stored
     * key is consulted (getApiKey() is invoked) without network access in the test runner.
     */
    @Test
    void testLlm_blankApiKey_fallsBackToStoredKey() {
        when(llmRuntimeConfig.getApiKey()).thenReturn("stored-sk-abcd1234");

        LlmSettingsUpdateRequest r = new LlmSettingsUpdateRequest();
        r.setProvider("mock");        // mock provider returns ok without any HTTP call
        r.setModelName("some-model");
        r.setApiKey("");              // blank → must fall back to llmRuntimeConfig.getApiKey()

        LlmTestResultVO result = service.testLlm(r);

        // Mock provider always succeeds; the important assertion is that the stored key was consulted.
        Assertions.assertTrue(result.isOk());
        verify(llmRuntimeConfig).getApiKey();
    }

    @Test
    void updateLlm_allowsPublicBaseUrl() {
        LlmSettingsUpdateRequest r = req("openai-compatible", "http://8.8.8.8/v1");
        Assertions.assertDoesNotThrow(() -> service.updateLlm(r));
        verify(llmRuntimeConfig).update(any(), any(), any(), any(), any());
    }
}
