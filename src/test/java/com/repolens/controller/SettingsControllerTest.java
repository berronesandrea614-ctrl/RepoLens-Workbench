package com.repolens.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.domain.dto.LlmSettingsUpdateRequest;
import com.repolens.domain.vo.EmbeddingSettingsVO;
import com.repolens.domain.vo.LlmSettingsVO;
import com.repolens.domain.vo.LlmTestResultVO;
import com.repolens.service.SettingsService;
import com.repolens.service.impl.SettingsServiceImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.repolens.controller.TestAuthUtils;

/**
 * SettingsController 单测（standaloneSetup，mock SettingsService）：
 * GET/PUT 返回脱敏 VO（永不回传完整 key），POST test 返回 ok/err；另含 mask 辅助方法测试。
 */
class SettingsControllerTest {

    private SettingsService settingsService;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setup() {
        settingsService = mock(SettingsService.class);
        SettingsController controller = new SettingsController(settingsService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(TestAuthUtils.fixedUserIdResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    void getLlm_returnsMaskedView() throws Exception {
        when(settingsService.getLlm()).thenReturn(LlmSettingsVO.builder()
                .provider("deepseek-compatible")
                .baseUrl("https://api.deepseek.com")
                .modelName("deepseek-chat")
                .timeoutMs(15000)
                .apiKeyMasked("****1234")
                .build());

        mockMvc.perform(get("/api/settings/llm").accept(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.provider").value("deepseek-compatible"))
                .andExpect(jsonPath("$.data.apiKeyMasked").value("****1234"))
                // 绝不出现完整 key 字段。
                .andExpect(jsonPath("$.data.apiKey").doesNotExist());
    }

    @Test
    void updateLlm_returnsMaskedView() throws Exception {
        when(settingsService.updateLlm(any(LlmSettingsUpdateRequest.class))).thenReturn(LlmSettingsVO.builder()
                .provider("openai-compatible")
                .baseUrl("https://api.openai.com/v1")
                .modelName("gpt-4o-mini")
                .timeoutMs(30000)
                .apiKeyMasked("****9999")
                .build());

        LlmSettingsUpdateRequest req = new LlmSettingsUpdateRequest();
        req.setProvider("openai-compatible");
        req.setBaseUrl("https://api.openai.com/v1");
        req.setApiKey("sk-full-secret-9999");
        req.setModelName("gpt-4o-mini");
        req.setTimeoutMs(30000);

        mockMvc.perform(put("/api/settings/llm")
                        .contentType(APPLICATION_JSON)
                        .accept(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.provider").value("openai-compatible"))
                .andExpect(jsonPath("$.data.apiKeyMasked").value("****9999"))
                .andExpect(jsonPath("$.data.apiKey").doesNotExist());
    }

    @Test
    void testLlm_returnsOk() throws Exception {
        when(settingsService.testLlm(any(LlmSettingsUpdateRequest.class)))
                .thenReturn(LlmTestResultVO.builder().ok(true).message("连接成功，模型可用").build());

        mockMvc.perform(post("/api/settings/llm/test")
                        .contentType(APPLICATION_JSON)
                        .accept(APPLICATION_JSON)
                        .content("{\"provider\":\"deepseek-compatible\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.ok").value(true))
                .andExpect(jsonPath("$.data.message").value("连接成功，模型可用"));
    }

    @Test
    void testLlm_returnsError() throws Exception {
        when(settingsService.testLlm(any(LlmSettingsUpdateRequest.class)))
                .thenReturn(LlmTestResultVO.builder().ok(false).message("HTTP 401: invalid api key").build());

        mockMvc.perform(post("/api/settings/llm/test")
                        .contentType(APPLICATION_JSON)
                        .accept(APPLICATION_JSON)
                        .content("{\"provider\":\"openai-compatible\",\"baseUrl\":\"https://x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(false))
                .andExpect(jsonPath("$.data.message").value("HTTP 401: invalid api key"));
    }

    @Test
    void getEmbedding_returnsReadOnlyInfo() throws Exception {
        when(settingsService.getEmbedding()).thenReturn(EmbeddingSettingsVO.builder()
                .provider("mock")
                .modelName("mock-embedding")
                .dimension(384)
                .mock(true)
                .build());

        mockMvc.perform(get("/api/settings/embedding").accept(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.provider").value("mock"))
                .andExpect(jsonPath("$.data.mock").value(true))
                .andExpect(jsonPath("$.data.dimension").value(384));
    }

    @Test
    void getEmbedding_serviceReflectsConfiguredRealProvider() {
        SettingsServiceImpl impl = new SettingsServiceImpl(null, objectMapper);
        ReflectionTestUtils.setField(impl, "embeddingProvider", "OpenAI-Compatible");
        ReflectionTestUtils.setField(impl, "embeddingModelName", "nomic-embed-text");
        ReflectionTestUtils.setField(impl, "embeddingDimension", 768);

        EmbeddingSettingsVO vo = impl.getEmbedding();

        Assertions.assertEquals("openai-compatible", vo.getProvider());
        Assertions.assertEquals("nomic-embed-text", vo.getModelName());
        Assertions.assertEquals(768, vo.getDimension());
        Assertions.assertFalse(vo.isMock());
    }

    @Test
    void getEmbedding_serviceDefaultsToMock() {
        SettingsServiceImpl impl = new SettingsServiceImpl(null, objectMapper);
        ReflectionTestUtils.setField(impl, "embeddingProvider", "mock");
        ReflectionTestUtils.setField(impl, "embeddingModelName", "mock-embedding");
        ReflectionTestUtils.setField(impl, "embeddingDimension", 384);

        EmbeddingSettingsVO vo = impl.getEmbedding();

        Assertions.assertTrue(vo.isMock());
        Assertions.assertEquals("mock", vo.getProvider());
    }

    @Test
    void maskApiKey_masksCorrectly() {
        Assertions.assertEquals("", SettingsServiceImpl.maskApiKey(null));
        Assertions.assertEquals("", SettingsServiceImpl.maskApiKey(""));
        Assertions.assertEquals("", SettingsServiceImpl.maskApiKey("   "));
        Assertions.assertEquals("****3456", SettingsServiceImpl.maskApiKey("sk-abc123456"));
        // 不足 4 位时用全部字符。
        Assertions.assertEquals("****ab", SettingsServiceImpl.maskApiKey("ab"));
    }
}
