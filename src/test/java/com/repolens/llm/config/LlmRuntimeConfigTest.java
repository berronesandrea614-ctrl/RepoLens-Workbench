package com.repolens.llm.config;

import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.AppSettingEntity;
import com.repolens.mapper.AppSettingMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LlmRuntimeConfig 单测（mock AppSettingMapper）。验证：
 * (a) 无 app_setting 行时 init 用环境默认播种；
 * (b) 有 app_setting 行时覆盖默认；
 * (c) update 持久化非空字段；
 * (d) update 传入空白 api-key 时保留现有 key、不落库该键；
 * (e) LOCAL_ONLY 模式下配置门拒绝云端 provider/非回环 baseUrl（P1 隐私）。
 */
@ExtendWith(MockitoExtension.class)
class LlmRuntimeConfigTest {

    @Mock
    private AppSettingMapper appSettingMapper;

    private LlmRuntimeConfig newConfigWithDefaults() {
        LlmRuntimeConfig cfg = new LlmRuntimeConfig(appSettingMapper);
        ReflectionTestUtils.setField(cfg, "defaultProvider", "mock");
        ReflectionTestUtils.setField(cfg, "defaultBaseUrl", "");
        ReflectionTestUtils.setField(cfg, "defaultApiKey", "");
        ReflectionTestUtils.setField(cfg, "defaultModelName", "mock-code-assistant");
        ReflectionTestUtils.setField(cfg, "defaultTimeoutMs", 15000);
        return cfg;
    }

    private static AppSettingEntity row(String k, String v) {
        AppSettingEntity e = new AppSettingEntity();
        e.setK(k);
        e.setV(v);
        return e;
    }

    @Test
    void init_seedsFromDefaults_whenNoRows() {
        when(appSettingMapper.selectList(null)).thenReturn(List.of());
        LlmRuntimeConfig cfg = newConfigWithDefaults();

        cfg.init();

        assertEquals("mock", cfg.getProvider());
        assertEquals("", cfg.getBaseUrl());
        assertEquals("", cfg.getApiKey());
        assertEquals("mock-code-assistant", cfg.getModelName());
        assertEquals(15000, cfg.getTimeoutMs());
    }

    @Test
    void init_overridesDefaults_fromAppSettingRows() {
        when(appSettingMapper.selectList(null)).thenReturn(List.of(
                row(LlmRuntimeConfig.KEY_PROVIDER, "deepseek-compatible"),
                row(LlmRuntimeConfig.KEY_BASE_URL, "https://api.deepseek.com"),
                row(LlmRuntimeConfig.KEY_API_KEY, "sk-secret-1234"),
                row(LlmRuntimeConfig.KEY_MODEL_NAME, "deepseek-chat"),
                row(LlmRuntimeConfig.KEY_TIMEOUT_MS, "20000")
        ));
        LlmRuntimeConfig cfg = newConfigWithDefaults();

        cfg.init();

        assertEquals("deepseek-compatible", cfg.getProvider());
        assertEquals("https://api.deepseek.com", cfg.getBaseUrl());
        assertEquals("sk-secret-1234", cfg.getApiKey());
        assertEquals("deepseek-chat", cfg.getModelName());
        assertEquals(20000, cfg.getTimeoutMs());
    }

    @Test
    void update_persistsProvidedFields() {
        when(appSettingMapper.selectList(null)).thenReturn(List.of());
        LlmRuntimeConfig cfg = newConfigWithDefaults();
        cfg.init();
        // 全新 key：selectById 返回 null -> insert 路径。
        when(appSettingMapper.selectById(any())).thenReturn(null);

        cfg.update("openai-compatible", "https://api.openai.com/v1", "sk-new-9999", "gpt-4o-mini", 30000);

        assertEquals("openai-compatible", cfg.getProvider());
        assertEquals("https://api.openai.com/v1", cfg.getBaseUrl());
        assertEquals("sk-new-9999", cfg.getApiKey());
        assertEquals("gpt-4o-mini", cfg.getModelName());
        assertEquals(30000, cfg.getTimeoutMs());

        // 5 个字段各 upsert 一次 -> 5 次 insert。
        ArgumentCaptor<AppSettingEntity> captor = ArgumentCaptor.forClass(AppSettingEntity.class);
        verify(appSettingMapper, org.mockito.Mockito.times(5)).insert(captor.capture());
        assertEquals(5, captor.getAllValues().size());
    }

    @Test
    void update_blankApiKey_keepsExistingAndDoesNotPersistKey() {
        when(appSettingMapper.selectList(null)).thenReturn(List.of(
                row(LlmRuntimeConfig.KEY_API_KEY, "sk-existing-abcd")
        ));
        LlmRuntimeConfig cfg = newConfigWithDefaults();
        cfg.init();
        assertEquals("sk-existing-abcd", cfg.getApiKey());
        when(appSettingMapper.selectById(any())).thenReturn(null);

        // 只改 provider，api-key 传空白 -> 保留原 key，不为 api-key 落库。
        cfg.update("ollama", null, "   ", null, null);

        assertEquals("ollama", cfg.getProvider());
        assertEquals("sk-existing-abcd", cfg.getApiKey());

        ArgumentCaptor<AppSettingEntity> captor = ArgumentCaptor.forClass(AppSettingEntity.class);
        verify(appSettingMapper).insert(captor.capture());
        assertEquals(LlmRuntimeConfig.KEY_PROVIDER, captor.getValue().getK());
        // 绝不更新 api-key 键。
        verify(appSettingMapper, never()).updateById(any(AppSettingEntity.class));
    }

    // ─── Config gate (P1 privacy) ─────────────────────────────────────────────

    @Test
    void isCloudProvider_detectsKnownProviders() {
        assertTrue(LlmRuntimeConfig.isCloudProvider("deepseek-compatible"));
        assertTrue(LlmRuntimeConfig.isCloudProvider("openai-compatible"));
        assertTrue(LlmRuntimeConfig.isCloudProvider("anthropic"));
        assertFalse(LlmRuntimeConfig.isCloudProvider("ollama"));
        assertFalse(LlmRuntimeConfig.isCloudProvider("mock"));
        assertFalse(LlmRuntimeConfig.isCloudProvider(null));
    }

    @Test
    void isLoopbackBaseUrl_detectsLoopback() {
        assertTrue(LlmRuntimeConfig.isLoopbackBaseUrl("http://localhost:11434/v1"));
        assertTrue(LlmRuntimeConfig.isLoopbackBaseUrl("http://127.0.0.1:11434"));
        assertFalse(LlmRuntimeConfig.isLoopbackBaseUrl("https://api.deepseek.com"));
        assertFalse(LlmRuntimeConfig.isLoopbackBaseUrl("https://api.openai.com/v1"));
    }

    @Test
    void update_localOnlyMode_rejectsCloudProvider() {
        when(appSettingMapper.selectList(null)).thenReturn(List.of());
        LlmRuntimeConfig cfg = newConfigWithDefaults();
        cfg.init();

        // Simulate LOCAL_ONLY mode in app_setting
        AppSettingEntity modeEntity = row("privacy.mode", "LOCAL_ONLY");
        when(appSettingMapper.selectById(any())).thenAnswer(inv -> {
            String key = (String) inv.getArgument(0);
            return "privacy.mode".equals(key) ? modeEntity : null;
        });

        BizException ex = assertThrows(BizException.class,
                () -> cfg.update("deepseek-compatible", null, null, null, null));
        assertTrue(ex.getMessage().contains("LOCAL_ONLY"));
    }

    @Test
    void update_localOnlyMode_rejectsNonLoopbackBaseUrl() {
        when(appSettingMapper.selectList(null)).thenReturn(List.of());
        LlmRuntimeConfig cfg = newConfigWithDefaults();
        cfg.init();

        AppSettingEntity modeEntity = row("privacy.mode", "LOCAL_ONLY");
        when(appSettingMapper.selectById(any())).thenAnswer(inv -> {
            String key = (String) inv.getArgument(0);
            return "privacy.mode".equals(key) ? modeEntity : null;
        });

        BizException ex = assertThrows(BizException.class,
                () -> cfg.update(null, "https://api.deepseek.com", null, null, null));
        assertTrue(ex.getMessage().contains("LOCAL_ONLY") || ex.getMessage().contains("baseUrl"));
    }

    @Test
    void update_localOnlyMode_allowsOllamaLocalhost() {
        when(appSettingMapper.selectList(null)).thenReturn(List.of());
        LlmRuntimeConfig cfg = newConfigWithDefaults();
        cfg.init();

        AppSettingEntity modeEntity = row("privacy.mode", "LOCAL_ONLY");
        when(appSettingMapper.selectById(any())).thenAnswer(inv -> {
            String key = (String) inv.getArgument(0);
            return "privacy.mode".equals(key) ? modeEntity : null;
        });

        // ollama on localhost should be allowed even in LOCAL_ONLY
        assertDoesNotThrow(() -> cfg.update("ollama", "http://localhost:11434/v1", null, null, null));
    }

    @Test
    void update_openMode_allowsCloudProvider() {
        when(appSettingMapper.selectList(null)).thenReturn(List.of());
        LlmRuntimeConfig cfg = newConfigWithDefaults();
        cfg.init();
        // No privacy.mode in app_setting → defaults to OPEN
        when(appSettingMapper.selectById(any())).thenReturn(null);

        // Should NOT throw in OPEN mode
        assertDoesNotThrow(() -> cfg.update("deepseek-compatible", "https://api.deepseek.com", null, null, null));
    }
}
