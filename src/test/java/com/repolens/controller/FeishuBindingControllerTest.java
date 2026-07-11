package com.repolens.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.common.exception.GlobalExceptionHandler;
import com.repolens.domain.vo.FeishuBindingVO;
import com.repolens.service.FeishuBridgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Contract tests for {@link FeishuBindingController} (standaloneSetup, no Spring context).
 * Verifies HTTP status, response structure, and that VO does not leak appSecretEnc.
 */
class FeishuBindingControllerTest {

    private FeishuBridgeService feishuBridgeService;
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @BeforeEach
    void setUp() {
        feishuBridgeService = mock(FeishuBridgeService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new FeishuBindingController(feishuBridgeService),
                        new FeishuPtyController(feishuBridgeService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(TestAuthUtils.fixedUserIdResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    // ── GET /bindings ─────────────────────────────────────────────────────────

    @Test
    void list_returns200WithBindings() throws Exception {
        FeishuBindingVO vo = makeVO();
        when(feishuBridgeService.list(1L, 10L)).thenReturn(List.of(vo));

        mockMvc.perform(get("/api/repos/10/feishu/bindings")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].appId").value("app1"))
                .andExpect(jsonPath("$.data[0].botName").value("TestBot"))
                .andExpect(jsonPath("$.data[0].status").value("CONNECTED"))
                // Verify secret is NOT in response
                .andExpect(jsonPath("$.data[0].appSecretEnc").doesNotExist());
    }

    // ── POST /bindings ────────────────────────────────────────────────────────

    @Test
    void create_returns200WithVO() throws Exception {
        FeishuBindingVO vo = makeVO();
        when(feishuBridgeService.create(eq(1L), eq(10L), any(), any(), any())).thenReturn(vo);

        String body = "{\"botName\":\"TestBot\",\"appId\":\"app1\",\"appSecret\":\"secret\"}";

        mockMvc.perform(post("/api/repos/10/feishu/bindings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.appId").value("app1"))
                .andExpect(jsonPath("$.data.appSecretEnc").doesNotExist());
    }

    // ── DELETE /bindings/{id} ─────────────────────────────────────────────────

    @Test
    void delete_returns200() throws Exception {
        doNothing().when(feishuBridgeService).delete(1L, 10L, 99L);

        mockMvc.perform(delete("/api/repos/10/feishu/bindings/99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    // ── POST /test-connection ─────────────────────────────────────────────────

    @Test
    void testConnection_success_returns200True() throws Exception {
        when(feishuBridgeService.testConnection(1L, 10L, "app1", "secret")).thenReturn(true);

        String body = "{\"appId\":\"app1\",\"appSecret\":\"secret\"}";

        mockMvc.perform(post("/api/repos/10/feishu/test-connection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(true));
    }

    @Test
    void testConnection_failure_returns200False() throws Exception {
        when(feishuBridgeService.testConnection(1L, 10L, "app1", "wrong")).thenReturn(false);

        String body = "{\"appId\":\"app1\",\"appSecret\":\"wrong\"}";

        mockMvc.perform(post("/api/repos/10/feishu/test-connection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(false));
    }

    // ── POST /pty-output ──────────────────────────────────────────────────────

    @Test
    void ptyOutput_returns200() throws Exception {
        doNothing().when(feishuBridgeService).onPtyOutput(1L, 10L, "some chunk");

        String body = "{\"chunk\":\"some chunk\"}";

        mockMvc.perform(post("/api/repos/10/feishu/pty-output")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static FeishuBindingVO makeVO() {
        FeishuBindingVO vo = new FeishuBindingVO();
        vo.setId(1L);
        vo.setRepoId(10L);
        vo.setBotName("TestBot");
        vo.setAppId("app1");
        vo.setStatus("CONNECTED");
        vo.setCreatedAt(LocalDateTime.now());
        return vo;
    }
}
