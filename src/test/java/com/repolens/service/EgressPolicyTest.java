package com.repolens.service;

import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.AppSettingEntity;
import com.repolens.domain.entity.EgressLogEntity;
import com.repolens.mapper.AppSettingMapper;
import com.repolens.mapper.EgressLogMapper;
import com.repolens.service.impl.EgressPolicyImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * EgressPolicy 单测：重点验证三档模式决策逻辑、白名单匹配、is_loopback 判定。
 * 使用 mock AppSettingMapper + EgressLogMapper，无 Spring 上下文，无 DB。
 */
class EgressPolicyTest {

    private AppSettingMapper appSettingMapper;
    private EgressLogMapper egressLogMapper;
    private EgressPolicyImpl policy;

    @BeforeEach
    void setup() {
        appSettingMapper = mock(AppSettingMapper.class);
        egressLogMapper = mock(EgressLogMapper.class);
        policy = new EgressPolicyImpl(appSettingMapper, egressLogMapper);
    }

    // ─── decide() pure logic ──────────────────────────────────────────────────

    @Test
    void decide_openMode_alwaysAllowed() {
        assertTrue(EgressPolicyImpl.decide(EgressLogEntity.MODE_OPEN, false, "api.deepseek.com", null));
        assertTrue(EgressPolicyImpl.decide(EgressLogEntity.MODE_OPEN, true, "localhost", null));
    }

    @Test
    void decide_localOnlyMode_allowsLoopback() {
        assertTrue(EgressPolicyImpl.decide(EgressLogEntity.MODE_LOCAL_ONLY, true, "localhost", null));
    }

    @Test
    void decide_localOnlyMode_blocksExternal() {
        assertFalse(EgressPolicyImpl.decide(EgressLogEntity.MODE_LOCAL_ONLY, false, "api.deepseek.com", null));
    }

    @Test
    void decide_allowlistMode_allowsLoopback() {
        assertTrue(EgressPolicyImpl.decide(EgressLogEntity.MODE_ALLOWLIST, true, "localhost", "other.com"));
    }

    @Test
    void decide_allowlistMode_allowsHostInList() {
        assertTrue(EgressPolicyImpl.decide(EgressLogEntity.MODE_ALLOWLIST, false, "api.deepseek.com", "api.deepseek.com,osv.dev"));
    }

    @Test
    void decide_allowlistMode_blocksHostNotInList() {
        assertFalse(EgressPolicyImpl.decide(EgressLogEntity.MODE_ALLOWLIST, false, "evil.example.com", "api.deepseek.com"));
    }

    @Test
    void decide_allowlistMode_emptyList_blocksExternal() {
        assertFalse(EgressPolicyImpl.decide(EgressLogEntity.MODE_ALLOWLIST, false, "api.deepseek.com", null));
        assertFalse(EgressPolicyImpl.decide(EgressLogEntity.MODE_ALLOWLIST, false, "api.deepseek.com", ""));
    }

    // ─── isInAllowlist() ─────────────────────────────────────────────────────

    @Test
    void isInAllowlist_caseInsensitive() {
        assertTrue(EgressPolicyImpl.isInAllowlist("API.DEEPSEEK.COM", "api.deepseek.com,osv.dev"));
    }

    @Test
    void isInAllowlist_nullAllowlist_returnsFalse() {
        assertFalse(EgressPolicyImpl.isInAllowlist("api.deepseek.com", null));
    }

    @Test
    void isInAllowlist_nullHost_returnsFalse() {
        assertFalse(EgressPolicyImpl.isInAllowlist(null, "api.deepseek.com"));
    }

    @Test
    void isInAllowlist_extraSpacesIgnored() {
        assertTrue(EgressPolicyImpl.isInAllowlist("api.deepseek.com", " api.deepseek.com , osv.dev "));
    }

    // ─── resolveHost() ───────────────────────────────────────────────────────

    @Test
    void resolveHost_localhost_isLoopback() {
        EgressPolicyImpl.ResolvedHost result = EgressPolicyImpl.resolveHost("localhost");
        assertTrue(result.isLoopback, "localhost should be loopback");
    }

    @Test
    void resolveHost_loopbackIp_isLoopback() {
        EgressPolicyImpl.ResolvedHost result = EgressPolicyImpl.resolveHost("127.0.0.1");
        assertTrue(result.isLoopback, "127.0.0.1 should be loopback");
    }

    @Test
    void resolveHost_publicHost_isNotLoopback() {
        // Use a known public IP directly to avoid DNS dependency in CI
        EgressPolicyImpl.ResolvedHost result = EgressPolicyImpl.resolveHost("8.8.8.8");
        assertFalse(result.isLoopback, "8.8.8.8 should not be loopback");
    }

    @Test
    void resolveHost_unknownHost_isNotLoopback() {
        EgressPolicyImpl.ResolvedHost result = EgressPolicyImpl.resolveHost("this-host-does-not-exist-xyz.example");
        assertFalse(result.isLoopback, "unresolvable host should not be loopback");
        assertNull(result.firstIp, "unresolvable host should have null firstIp");
    }

    @Test
    void resolveHost_nullHost_isNotLoopback() {
        EgressPolicyImpl.ResolvedHost result = EgressPolicyImpl.resolveHost(null);
        assertFalse(result.isLoopback);
    }

    // ─── getMode() with app_setting ──────────────────────────────────────────

    @Test
    void getMode_noSetting_returnsOpen() {
        when(appSettingMapper.selectById(EgressPolicyImpl.KEY_PRIVACY_MODE)).thenReturn(null);
        assertEquals(EgressLogEntity.MODE_OPEN, policy.getMode());
    }

    @Test
    void getMode_localOnlySetting_returnsLocalOnly() {
        AppSettingEntity entity = new AppSettingEntity();
        entity.setK(EgressPolicyImpl.KEY_PRIVACY_MODE);
        entity.setV("LOCAL_ONLY");
        when(appSettingMapper.selectById(EgressPolicyImpl.KEY_PRIVACY_MODE)).thenReturn(entity);
        assertEquals(EgressLogEntity.MODE_LOCAL_ONLY, policy.getMode());
    }

    @Test
    void getMode_invalidSetting_fallsBackToOpen() {
        AppSettingEntity entity = new AppSettingEntity();
        entity.setK(EgressPolicyImpl.KEY_PRIVACY_MODE);
        entity.setV("INVALID_VALUE");
        when(appSettingMapper.selectById(EgressPolicyImpl.KEY_PRIVACY_MODE)).thenReturn(entity);
        assertEquals(EgressLogEntity.MODE_OPEN, policy.getMode());
    }

    // ─── checkAndLog() integration ────────────────────────────────────────────

    @Test
    void checkAndLog_openMode_logsAndAllows() {
        // mode = OPEN
        when(appSettingMapper.selectById(EgressPolicyImpl.KEY_PRIVACY_MODE)).thenReturn(null);
        when(appSettingMapper.selectById(EgressPolicyImpl.KEY_PRIVACY_ALLOWLIST)).thenReturn(null);

        // Should NOT throw
        assertDoesNotThrow(() -> policy.checkAndLog("api.deepseek.com", 443, EgressLogEntity.PURPOSE_LLM, "deepseek-chat"));

        // Should insert log with allowed=true
        ArgumentCaptor<EgressLogEntity> captor = ArgumentCaptor.forClass(EgressLogEntity.class);
        verify(egressLogMapper).insert(captor.capture());
        EgressLogEntity logged = captor.getValue();
        assertTrue(Boolean.TRUE.equals(logged.getAllowed()));
        assertEquals("LLM", logged.getPurpose());
        assertEquals("api.deepseek.com", logged.getDestHost());
    }

    @Test
    void checkAndLog_localOnlyMode_loopback_allows() {
        AppSettingEntity modeEntity = new AppSettingEntity();
        modeEntity.setK(EgressPolicyImpl.KEY_PRIVACY_MODE);
        modeEntity.setV("LOCAL_ONLY");
        when(appSettingMapper.selectById(EgressPolicyImpl.KEY_PRIVACY_MODE)).thenReturn(modeEntity);
        when(appSettingMapper.selectById(EgressPolicyImpl.KEY_PRIVACY_ALLOWLIST)).thenReturn(null);

        // localhost is loopback → should NOT throw
        assertDoesNotThrow(() -> policy.checkAndLog("localhost", 11434, EgressLogEntity.PURPOSE_LLM, "ollama"));

        ArgumentCaptor<EgressLogEntity> captor = ArgumentCaptor.forClass(EgressLogEntity.class);
        verify(egressLogMapper).insert(captor.capture());
        assertTrue(Boolean.TRUE.equals(captor.getValue().getAllowed()));
    }

    @Test
    void checkAndLog_localOnlyMode_external_blocksAndLogs() {
        AppSettingEntity modeEntity = new AppSettingEntity();
        modeEntity.setK(EgressPolicyImpl.KEY_PRIVACY_MODE);
        modeEntity.setV("LOCAL_ONLY");
        when(appSettingMapper.selectById(EgressPolicyImpl.KEY_PRIVACY_MODE)).thenReturn(modeEntity);
        when(appSettingMapper.selectById(EgressPolicyImpl.KEY_PRIVACY_ALLOWLIST)).thenReturn(null);

        // 8.8.8.8 is not loopback → should throw BizException
        BizException ex = assertThrows(BizException.class,
                () -> policy.checkAndLog("8.8.8.8", 443, EgressLogEntity.PURPOSE_LLM, "model"));
        assertTrue(ex.getMessage().contains("LOCAL_ONLY"));

        // Log should be written with allowed=false
        ArgumentCaptor<EgressLogEntity> captor = ArgumentCaptor.forClass(EgressLogEntity.class);
        verify(egressLogMapper).insert(captor.capture());
        assertTrue(Boolean.FALSE.equals(captor.getValue().getAllowed()));
    }

    @Test
    void checkAndLog_logWriteFailure_doesNotBlockMainFlow() {
        // mode = LOCAL_ONLY, loopback host → should be allowed
        AppSettingEntity modeEntity = new AppSettingEntity();
        modeEntity.setK(EgressPolicyImpl.KEY_PRIVACY_MODE);
        modeEntity.setV("LOCAL_ONLY");
        when(appSettingMapper.selectById(EgressPolicyImpl.KEY_PRIVACY_MODE)).thenReturn(modeEntity);
        when(appSettingMapper.selectById(EgressPolicyImpl.KEY_PRIVACY_ALLOWLIST)).thenReturn(null);
        // Simulate log write failure (disambiguate insert overload)
        doThrow(new RuntimeException("DB down")).when(egressLogMapper).insert(any(EgressLogEntity.class));

        // Should NOT throw even though DB failed (failure-safe + loopback allowed)
        assertDoesNotThrow(() -> policy.checkAndLog("127.0.0.1", 11434, EgressLogEntity.PURPOSE_LLM, "model"));
    }

    @Test
    void checkAndLog_allowlistMode_hostInList_allows() {
        AppSettingEntity modeEntity = new AppSettingEntity();
        modeEntity.setK(EgressPolicyImpl.KEY_PRIVACY_MODE);
        modeEntity.setV("ALLOWLIST");
        when(appSettingMapper.selectById(EgressPolicyImpl.KEY_PRIVACY_MODE)).thenReturn(modeEntity);

        AppSettingEntity listEntity = new AppSettingEntity();
        listEntity.setK(EgressPolicyImpl.KEY_PRIVACY_ALLOWLIST);
        listEntity.setV("api.deepseek.com,osv.dev");
        when(appSettingMapper.selectById(EgressPolicyImpl.KEY_PRIVACY_ALLOWLIST)).thenReturn(listEntity);

        assertDoesNotThrow(() -> policy.checkAndLog("api.deepseek.com", 443, EgressLogEntity.PURPOSE_LLM, "deepseek"));

        ArgumentCaptor<EgressLogEntity> captor = ArgumentCaptor.forClass(EgressLogEntity.class);
        verify(egressLogMapper).insert(captor.capture());
        assertTrue(Boolean.TRUE.equals(captor.getValue().getAllowed()));
    }

    @Test
    void checkAndLog_allowlistMode_hostNotInList_blocks() {
        AppSettingEntity modeEntity = new AppSettingEntity();
        modeEntity.setK(EgressPolicyImpl.KEY_PRIVACY_MODE);
        modeEntity.setV("ALLOWLIST");
        when(appSettingMapper.selectById(EgressPolicyImpl.KEY_PRIVACY_MODE)).thenReturn(modeEntity);

        AppSettingEntity listEntity = new AppSettingEntity();
        listEntity.setK(EgressPolicyImpl.KEY_PRIVACY_ALLOWLIST);
        listEntity.setV("api.deepseek.com");
        when(appSettingMapper.selectById(EgressPolicyImpl.KEY_PRIVACY_ALLOWLIST)).thenReturn(listEntity);

        assertThrows(BizException.class,
                () -> policy.checkAndLog("8.8.8.8", 443, EgressLogEntity.PURPOSE_EMBEDDING, "model"));

        ArgumentCaptor<EgressLogEntity> captor = ArgumentCaptor.forClass(EgressLogEntity.class);
        verify(egressLogMapper).insert(captor.capture());
        assertTrue(Boolean.FALSE.equals(captor.getValue().getAllowed()));
    }
}
