package com.repolens.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.common.exception.GlobalExceptionHandler;
import com.repolens.domain.entity.AppSettingEntity;
import com.repolens.domain.entity.EgressLogEntity;
import com.repolens.domain.vo.PrivacyVerifyVO;
import com.repolens.domain.vo.PrivacyVerifyVO.CheckResult;
import com.repolens.mapper.AppSettingMapper;
import com.repolens.mapper.EgressLogMapper;
import com.repolens.service.EgressPolicy;
import com.repolens.service.impl.PrivacyVerifyServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * PrivacyController 契约测试（standaloneSetup，无 Spring 上下文）。
 * 验证：GET /status, GET /egress, PUT /mode, GET /verify, GET /report 的响应契约。
 */
class PrivacyControllerTest {

    private EgressPolicy egressPolicy;
    private EgressLogMapper egressLogMapper;
    private AppSettingMapper appSettingMapper;
    private PrivacyVerifyServiceImpl privacyVerifyService;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @BeforeEach
    void setup() {
        egressPolicy = mock(EgressPolicy.class);
        egressLogMapper = mock(EgressLogMapper.class);
        appSettingMapper = mock(AppSettingMapper.class);
        privacyVerifyService = mock(PrivacyVerifyServiceImpl.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new PrivacyController(egressPolicy, egressLogMapper, appSettingMapper, privacyVerifyService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(TestAuthUtils.fixedUserIdResolver())
                .setMessageConverters(
                        new StringHttpMessageConverter(java.nio.charset.StandardCharsets.UTF_8),
                        new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    // ─── GET /status ─────────────────────────────────────────────────────────

    @Test
    void getStatus_returnsCurrentModeAndCounts() throws Exception {
        when(egressPolicy.getMode()).thenReturn(EgressLogEntity.MODE_OPEN);
        when(egressLogMapper.countTotal()).thenReturn(42L);
        when(egressLogMapper.countBlocked()).thenReturn(3L);

        mockMvc.perform(get("/api/privacy/status").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.mode").value("OPEN"))
                .andExpect(jsonPath("$.data.totalCount").value(42))
                .andExpect(jsonPath("$.data.blockedCount").value(3))
                .andExpect(jsonPath("$.data.allowedCount").value(39));
    }

    @Test
    void getStatus_localOnlyMode() throws Exception {
        when(egressPolicy.getMode()).thenReturn(EgressLogEntity.MODE_LOCAL_ONLY);
        when(egressLogMapper.countTotal()).thenReturn(5L);
        when(egressLogMapper.countBlocked()).thenReturn(5L);

        mockMvc.perform(get("/api/privacy/status").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("LOCAL_ONLY"))
                .andExpect(jsonPath("$.data.blockedCount").value(5));
    }

    @Test
    void getStatus_dbFailureFallsBackToZeroCounts() throws Exception {
        when(egressPolicy.getMode()).thenReturn(EgressLogEntity.MODE_OPEN);
        when(egressLogMapper.countTotal()).thenThrow(new RuntimeException("DB down"));
        when(egressLogMapper.countBlocked()).thenThrow(new RuntimeException("DB down"));

        // Should still return 200, just with 0 counts
        mockMvc.perform(get("/api/privacy/status").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(0))
                .andExpect(jsonPath("$.data.blockedCount").value(0));
    }

    // ─── GET /egress ─────────────────────────────────────────────────────────

    @Test
    void getEgress_returnsEgressLogs() throws Exception {
        EgressLogEntity row = new EgressLogEntity();
        row.setId(1L);
        row.setTs(LocalDateTime.of(2026, 7, 6, 12, 0, 0));
        row.setPurpose("LLM");
        row.setDestHost("api.deepseek.com");
        row.setDestPort(443);
        row.setResolvedIp("1.2.3.4");
        row.setIsLoopback(false);
        row.setAllowed(false);
        row.setPrivacyMode("LOCAL_ONLY");
        row.setModelName("deepseek-chat");
        when(egressLogMapper.selectList(any())).thenReturn(List.of(row));

        mockMvc.perform(get("/api/privacy/egress").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].destHost").value("api.deepseek.com"))
                .andExpect(jsonPath("$.data[0].allowed").value(false))
                .andExpect(jsonPath("$.data[0].privacyMode").value("LOCAL_ONLY"))
                .andExpect(jsonPath("$.data[0].purpose").value("LLM"));
    }

    @Test
    void getEgress_limitCappedAt200() throws Exception {
        when(egressLogMapper.selectList(any())).thenReturn(List.of());
        // limit=500 → capped to 200 → should not error
        mockMvc.perform(get("/api/privacy/egress?limit=500").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    // ─── PUT /mode ───────────────────────────────────────────────────────────

    @Test
    void updateMode_validMode_persistsAndReturnsStatus() throws Exception {
        when(appSettingMapper.selectById(anyString())).thenReturn(null);
        when(egressPolicy.getMode()).thenReturn(EgressLogEntity.MODE_LOCAL_ONLY);
        when(egressLogMapper.countTotal()).thenReturn(0L);
        when(egressLogMapper.countBlocked()).thenReturn(0L);

        mockMvc.perform(put("/api/privacy/mode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"LOCAL_ONLY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("LOCAL_ONLY"));

        // Use ArgumentCaptor to avoid insert() overload ambiguity (insert(T) vs insert(Collection<T>))
        ArgumentCaptor<AppSettingEntity> captor = ArgumentCaptor.forClass(AppSettingEntity.class);
        verify(appSettingMapper, atLeastOnce()).insert(captor.capture());
        boolean foundModeInsert = captor.getAllValues().stream()
                .anyMatch(e -> "privacy.mode".equals(e.getK()) && "LOCAL_ONLY".equals(e.getV()));
        assertTrue(foundModeInsert, "Expected insert of privacy.mode=LOCAL_ONLY");
    }

    @Test
    void updateMode_invalidMode_returns400() throws Exception {
        mockMvc.perform(put("/api/privacy/mode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"INVALID\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateMode_missingMode_returns400() throws Exception {
        mockMvc.perform(put("/api/privacy/mode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateMode_allowlistWithHosts_persistsAllowlist() throws Exception {
        when(appSettingMapper.selectById(anyString())).thenReturn(null);
        when(egressPolicy.getMode()).thenReturn(EgressLogEntity.MODE_ALLOWLIST);
        when(egressLogMapper.countTotal()).thenReturn(0L);
        when(egressLogMapper.countBlocked()).thenReturn(0L);

        mockMvc.perform(put("/api/privacy/mode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"ALLOWLIST\",\"allowlist\":\"api.deepseek.com,osv.dev\"}"))
                .andExpect(status().isOk());

        // Use ArgumentCaptor to avoid insert() overload ambiguity
        ArgumentCaptor<AppSettingEntity> captor = ArgumentCaptor.forClass(AppSettingEntity.class);
        verify(appSettingMapper, atLeastOnce()).insert(captor.capture());
        boolean foundAllowlist = captor.getAllValues().stream()
                .anyMatch(e -> "privacy.allowlist".equals(e.getK())
                        && "api.deepseek.com,osv.dev".equals(e.getV()));
        assertTrue(foundAllowlist, "Expected insert of privacy.allowlist");
    }

    // ─── GET /verify ──────────────────────────────────────────────────────────

    @Test
    void verify_verdictPass_returns200WithPassResult() throws Exception {
        PrivacyVerifyVO vo = PrivacyVerifyVO.builder()
                .mode(EgressLogEntity.MODE_LOCAL_ONLY)
                .llmProviderIsLocal(CheckResult.builder().passed(true).reason("provider=ollama").build())
                .baseUrlIsLoopback(CheckResult.builder().passed(true).reason("loopback").build())
                .ollamaReachable(CheckResult.builder().passed(true).reason("HTTP 200").build())
                .recentEgressAllExternalBlocked(CheckResult.builder().passed(true).reason("0 leaks").build())
                .verdict(true)
                .note("app-layer only")
                .checkedAt("2026-07-05T10:00:00")
                .build();
        when(privacyVerifyService.verify()).thenReturn(vo);

        mockMvc.perform(get("/api/privacy/verify").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.verdict").value(true))
                .andExpect(jsonPath("$.data.mode").value("LOCAL_ONLY"))
                .andExpect(jsonPath("$.data.llmProviderIsLocal.passed").value(true))
                .andExpect(jsonPath("$.data.recentEgressAllExternalBlocked.passed").value(true));
    }

    @Test
    void verify_verdictFail_returns200WithFailResult() throws Exception {
        PrivacyVerifyVO vo = PrivacyVerifyVO.builder()
                .mode(EgressLogEntity.MODE_OPEN)
                .llmProviderIsLocal(CheckResult.builder().passed(false).reason("cloud provider").build())
                .baseUrlIsLoopback(CheckResult.builder().passed(false).reason("non-loopback").build())
                .ollamaReachable(CheckResult.builder().passed(false).reason("no url").build())
                .recentEgressAllExternalBlocked(CheckResult.builder().passed(false).reason("mode not LOCAL_ONLY").build())
                .verdict(false)
                .note("app-layer only")
                .checkedAt("2026-07-05T10:00:00")
                .build();
        when(privacyVerifyService.verify()).thenReturn(vo);

        mockMvc.perform(get("/api/privacy/verify").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verdict").value(false))
                .andExpect(jsonPath("$.data.llmProviderIsLocal.passed").value(false));
    }

    // ─── GET /report ──────────────────────────────────────────────────────────

    @Test
    void report_jsonFormat_returns200WithJsonBody() throws Exception {
        PrivacyVerifyVO vo = PrivacyVerifyVO.builder()
                .mode(EgressLogEntity.MODE_LOCAL_ONLY)
                .llmProviderIsLocal(CheckResult.builder().passed(true).reason("ollama").build())
                .baseUrlIsLoopback(CheckResult.builder().passed(true).reason("loopback").build())
                .ollamaReachable(CheckResult.builder().passed(true).reason("200").build())
                .recentEgressAllExternalBlocked(CheckResult.builder().passed(true).reason("clean").build())
                .verdict(true)
                .note("app-layer")
                .checkedAt("2026-07-05T10:00:00")
                .build();
        when(privacyVerifyService.verify()).thenReturn(vo);
        when(egressLogMapper.countTotal()).thenReturn(10L);
        when(egressLogMapper.countBlocked()).thenReturn(10L);
        when(egressLogMapper.recentExternalHosts(any())).thenReturn(List.of());
        when(egressLogMapper.recentExternalAllowedHosts(any())).thenReturn(List.of());
        when(egressLogMapper.minTs()).thenReturn(null);
        when(egressLogMapper.maxTs()).thenReturn(null);

        mockMvc.perform(get("/api/privacy/report?format=json").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verdict").value("PASS"))
                .andExpect(jsonPath("$.data.mode").value("LOCAL_ONLY"))
                .andExpect(jsonPath("$.data.boundaryDisclaimer").exists())
                .andExpect(jsonPath("$.data.jfrInstructions").exists());
    }

    @Test
    void report_txtFormat_returnsAttachment() throws Exception {
        PrivacyVerifyVO vo = PrivacyVerifyVO.builder()
                .mode(EgressLogEntity.MODE_LOCAL_ONLY)
                .llmProviderIsLocal(CheckResult.builder().passed(true).reason("ollama").build())
                .baseUrlIsLoopback(CheckResult.builder().passed(true).reason("loopback").build())
                .ollamaReachable(CheckResult.builder().passed(false).reason("refused").build())
                .recentEgressAllExternalBlocked(CheckResult.builder().passed(true).reason("clean").build())
                .verdict(false)
                .note("app-layer")
                .checkedAt("2026-07-05T10:00:00")
                .build();
        when(privacyVerifyService.verify()).thenReturn(vo);
        when(egressLogMapper.countTotal()).thenReturn(5L);
        when(egressLogMapper.countBlocked()).thenReturn(5L);
        when(egressLogMapper.recentExternalHosts(any())).thenReturn(List.of());
        when(egressLogMapper.recentExternalAllowedHosts(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/privacy/report?format=txt").accept(MediaType.TEXT_PLAIN))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(".txt")));
    }

    @Test
    void report_defaultFormat_isJson() throws Exception {
        PrivacyVerifyVO vo = PrivacyVerifyVO.builder()
                .mode(EgressLogEntity.MODE_OPEN)
                .llmProviderIsLocal(CheckResult.builder().passed(false).reason("cloud").build())
                .baseUrlIsLoopback(CheckResult.builder().passed(false).reason("non-loop").build())
                .ollamaReachable(CheckResult.builder().passed(false).reason("no url").build())
                .recentEgressAllExternalBlocked(CheckResult.builder().passed(false).reason("open mode").build())
                .verdict(false)
                .note("app-layer")
                .checkedAt("2026-07-05T10:00:00")
                .build();
        when(privacyVerifyService.verify()).thenReturn(vo);
        when(egressLogMapper.countTotal()).thenReturn(0L);
        when(egressLogMapper.countBlocked()).thenReturn(0L);
        when(egressLogMapper.recentExternalHosts(any())).thenReturn(List.of());
        when(egressLogMapper.recentExternalAllowedHosts(any())).thenReturn(List.of());
        when(egressLogMapper.minTs()).thenReturn(null);
        when(egressLogMapper.maxTs()).thenReturn(null);

        mockMvc.perform(get("/api/privacy/report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportType").exists());
    }
}
