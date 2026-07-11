package com.repolens.service;

import com.repolens.domain.entity.EgressLogEntity;
import com.repolens.domain.vo.PrivacyVerifyVO.CheckResult;
import com.repolens.mapper.EgressLogMapper;
import com.repolens.service.impl.PrivacyVerifyServiceImpl;
import com.repolens.llm.config.LlmRuntimeConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * PrivacyVerifyServiceImpl 纯逻辑单测。
 * 只测 static 方法（checkLlmProviderIsLocal、checkBaseUrlIsLoopback、aggregateVerdict），
 * 无 Spring 上下文、无 DB、无网络。
 */
class PrivacyVerifyServiceTest {

    // ─── checkLlmProviderIsLocal ─────────────────────────────────────────────

    @Test
    void checkLlmProviderIsLocal_mockProvider_passes() {
        CheckResult r = PrivacyVerifyServiceImpl.checkLlmProviderIsLocal("mock", new ArrayList<>());
        assertTrue(r.isPassed());
        assertTrue(r.getReason().contains("mock"));
    }

    @Test
    void checkLlmProviderIsLocal_nullProvider_passes() {
        CheckResult r = PrivacyVerifyServiceImpl.checkLlmProviderIsLocal(null, new ArrayList<>());
        assertTrue(r.isPassed());
    }

    @Test
    void checkLlmProviderIsLocal_emptyProvider_passes() {
        CheckResult r = PrivacyVerifyServiceImpl.checkLlmProviderIsLocal("", new ArrayList<>());
        assertTrue(r.isPassed());
    }

    @Test
    void checkLlmProviderIsLocal_ollamaProvider_passes() {
        CheckResult r = PrivacyVerifyServiceImpl.checkLlmProviderIsLocal("ollama", new ArrayList<>());
        assertTrue(r.isPassed());
        assertTrue(r.getReason().contains("ollama"));
    }

    @Test
    void checkLlmProviderIsLocal_deepseekProvider_fails() {
        CheckResult r = PrivacyVerifyServiceImpl.checkLlmProviderIsLocal("deepseek-compatible", new ArrayList<>());
        assertFalse(r.isPassed());
        assertTrue(r.getReason().contains("云端"));
    }

    @Test
    void checkLlmProviderIsLocal_openaiProvider_fails() {
        CheckResult r = PrivacyVerifyServiceImpl.checkLlmProviderIsLocal("openai", new ArrayList<>());
        assertFalse(r.isPassed());
    }

    @Test
    void checkLlmProviderIsLocal_anthropicProvider_fails() {
        CheckResult r = PrivacyVerifyServiceImpl.checkLlmProviderIsLocal("anthropic", new ArrayList<>());
        assertFalse(r.isPassed());
    }

    @Test
    void checkLlmProviderIsLocal_customLocalProvider_passes() {
        // Non-cloud custom name
        CheckResult r = PrivacyVerifyServiceImpl.checkLlmProviderIsLocal("local-llm", new ArrayList<>());
        assertTrue(r.isPassed());
    }

    // ─── checkBaseUrlIsLoopback ──────────────────────────────────────────────

    @Test
    void checkBaseUrlIsLoopback_emptyUrl_passes() {
        CheckResult r = PrivacyVerifyServiceImpl.checkBaseUrlIsLoopback("", new ArrayList<>());
        assertTrue(r.isPassed());
        assertTrue(r.getReason().contains("mock"));
    }

    @Test
    void checkBaseUrlIsLoopback_nullUrl_passes() {
        CheckResult r = PrivacyVerifyServiceImpl.checkBaseUrlIsLoopback(null, new ArrayList<>());
        assertTrue(r.isPassed());
    }

    @Test
    void checkBaseUrlIsLoopback_localhostUrl_passes() {
        CheckResult r = PrivacyVerifyServiceImpl.checkBaseUrlIsLoopback("http://localhost:11434/v1", new ArrayList<>());
        assertTrue(r.isPassed());
    }

    @Test
    void checkBaseUrlIsLoopback_127Url_passes() {
        CheckResult r = PrivacyVerifyServiceImpl.checkBaseUrlIsLoopback("http://127.0.0.1:11434", new ArrayList<>());
        assertTrue(r.isPassed());
    }

    @Test
    void checkBaseUrlIsLoopback_cloudUrl_fails() {
        CheckResult r = PrivacyVerifyServiceImpl.checkBaseUrlIsLoopback("https://api.deepseek.com", new ArrayList<>());
        assertFalse(r.isPassed());
        assertTrue(r.getReason().contains("非回环"));
    }

    @Test
    void checkBaseUrlIsLoopback_openaiUrl_fails() {
        CheckResult r = PrivacyVerifyServiceImpl.checkBaseUrlIsLoopback("https://api.openai.com/v1", new ArrayList<>());
        assertFalse(r.isPassed());
    }

    // ─── aggregateVerdict ────────────────────────────────────────────────────

    @Test
    void aggregateVerdict_allPassLocalOnly_true() {
        assertTrue(PrivacyVerifyServiceImpl.aggregateVerdict(
                true, true, true, true, EgressLogEntity.MODE_LOCAL_ONLY));
    }

    @Test
    void aggregateVerdict_oneCheckFails_false() {
        assertFalse(PrivacyVerifyServiceImpl.aggregateVerdict(
                true, true, false, true, EgressLogEntity.MODE_LOCAL_ONLY));
    }

    @Test
    void aggregateVerdict_modeIsOpen_false() {
        assertFalse(PrivacyVerifyServiceImpl.aggregateVerdict(
                true, true, true, true, EgressLogEntity.MODE_OPEN));
    }

    @Test
    void aggregateVerdict_modeIsAllowlist_false() {
        assertFalse(PrivacyVerifyServiceImpl.aggregateVerdict(
                true, true, true, true, EgressLogEntity.MODE_ALLOWLIST));
    }

    @Test
    void aggregateVerdict_allFailLocalOnly_false() {
        assertFalse(PrivacyVerifyServiceImpl.aggregateVerdict(
                false, false, false, false, EgressLogEntity.MODE_LOCAL_ONLY));
    }

    @Test
    void aggregateVerdict_llmProviderFailLocalOnly_false() {
        assertFalse(PrivacyVerifyServiceImpl.aggregateVerdict(
                false, true, true, true, EgressLogEntity.MODE_LOCAL_ONLY));
    }

    @Test
    void aggregateVerdict_egressLeakedLocalOnly_false() {
        assertFalse(PrivacyVerifyServiceImpl.aggregateVerdict(
                true, true, true, false, EgressLogEntity.MODE_LOCAL_ONLY));
    }

    // ─── checkRecentEgressAllExternalBlocked (unit; mocked mapper) ────────────

    @Test
    void checkRecentEgressAllExternalBlocked_nonLocalOnlyMode_fails() {
        EgressLogMapper mapper = mock(EgressLogMapper.class);
        EgressPolicy policy = mock(EgressPolicy.class);
        when(policy.getMode()).thenReturn("OPEN");
        LlmRuntimeConfig cfg = mock(LlmRuntimeConfig.class);
        PrivacyVerifyServiceImpl svc = new PrivacyVerifyServiceImpl(cfg, policy, mapper);

        CheckResult r = svc.checkRecentEgressAllExternalBlocked("OPEN", new ArrayList<>());
        assertFalse(r.isPassed());
        assertTrue(r.getReason().contains("LOCAL_ONLY"));
    }

    @Test
    void checkRecentEgressAllExternalBlocked_localOnlyNoLeaks_passes() {
        EgressLogMapper mapper = mock(EgressLogMapper.class);
        when(mapper.countRecentNonLoopbackAllowed(any())).thenReturn(0L);
        EgressPolicy policy = mock(EgressPolicy.class);
        LlmRuntimeConfig cfg = mock(LlmRuntimeConfig.class);
        PrivacyVerifyServiceImpl svc = new PrivacyVerifyServiceImpl(cfg, policy, mapper);

        CheckResult r = svc.checkRecentEgressAllExternalBlocked(EgressLogEntity.MODE_LOCAL_ONLY, new ArrayList<>());
        assertTrue(r.isPassed());
        assertTrue(r.getReason().contains("无非回环放行"));
    }

    @Test
    void checkRecentEgressAllExternalBlocked_localOnlyWithLeaks_fails() {
        EgressLogMapper mapper = mock(EgressLogMapper.class);
        when(mapper.countRecentNonLoopbackAllowed(any())).thenReturn(3L);
        EgressPolicy policy = mock(EgressPolicy.class);
        LlmRuntimeConfig cfg = mock(LlmRuntimeConfig.class);
        PrivacyVerifyServiceImpl svc = new PrivacyVerifyServiceImpl(cfg, policy, mapper);

        CheckResult r = svc.checkRecentEgressAllExternalBlocked(EgressLogEntity.MODE_LOCAL_ONLY, new ArrayList<>());
        assertFalse(r.isPassed());
        assertTrue(r.getReason().contains("3"));
    }

    @Test
    void checkRecentEgressAllExternalBlocked_mapperThrows_failsSafe() {
        EgressLogMapper mapper = mock(EgressLogMapper.class);
        when(mapper.countRecentNonLoopbackAllowed(any())).thenThrow(new RuntimeException("DB down"));
        EgressPolicy policy = mock(EgressPolicy.class);
        LlmRuntimeConfig cfg = mock(LlmRuntimeConfig.class);
        PrivacyVerifyServiceImpl svc = new PrivacyVerifyServiceImpl(cfg, policy, mapper);

        List<String> warnings = new ArrayList<>();
        CheckResult r = svc.checkRecentEgressAllExternalBlocked(EgressLogEntity.MODE_LOCAL_ONLY, warnings);
        assertFalse(r.isPassed());
        assertFalse(warnings.isEmpty()); // Warning captured
    }
}
