package com.repolens.controller;

import com.repolens.service.impl.support.MemoryMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.repolens.controller.TestAuthUtils;

/**
 * MetricsController 单元测试。
 * 验证：
 * - GET /api/metrics/memory 返回当前快照
 * - 返回的 JSON 包含 submitted, completed, failed, skipped 计数器
 * - 结果包裹在 Result<T> 中
 * - X-User-Id 头正确处理（无需权限校验）
 */
class MetricsControllerTest {

    private MemoryMetrics memoryMetrics;
    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        memoryMetrics = new MemoryMetrics();
        mockMvc = MockMvcBuilders.standaloneSetup(new MetricsController(memoryMetrics))
                .setCustomArgumentResolvers(TestAuthUtils.fixedUserIdResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    void getMemoryMetricsReturnsEmptySnapshot() throws Exception {
        mockMvc.perform(get("/api/metrics/memory")
                        .accept(APPLICATION_JSON)
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("OK"))
                .andExpect(jsonPath("$.data.submitted").value(0))
                .andExpect(jsonPath("$.data.completed").value(0))
                .andExpect(jsonPath("$.data.failed").value(0))
                .andExpect(jsonPath("$.data.skipped").value(0));
    }

    @Test
    void getMemoryMetricsReturnsIncrementedCounters() throws Exception {
        // Simulate some work
        memoryMetrics.incrementSubmitted();
        memoryMetrics.incrementSubmitted();
        memoryMetrics.incrementCompleted();
        memoryMetrics.incrementFailed();
        memoryMetrics.incrementSkipped();

        mockMvc.perform(get("/api/metrics/memory")
                        .accept(APPLICATION_JSON)
                        .header("X-User-Id", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.submitted").value(2))
                .andExpect(jsonPath("$.data.completed").value(1))
                .andExpect(jsonPath("$.data.failed").value(1))
                .andExpect(jsonPath("$.data.skipped").value(1));
    }

    @Test
    void getMemoryMetricsWorksWithoutUserIdHeader() throws Exception {
        // X-User-Id defaults to "1" if not provided
        memoryMetrics.incrementSubmitted();
        memoryMetrics.incrementSubmitted();
        memoryMetrics.incrementCompleted();

        mockMvc.perform(get("/api/metrics/memory")
                        .accept(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.submitted").value(2))
                .andExpect(jsonPath("$.data.completed").value(1))
                .andExpect(jsonPath("$.data.failed").value(0))
                .andExpect(jsonPath("$.data.skipped").value(0));
    }

    @Test
    void resultContainsTimestamp() throws Exception {
        mockMvc.perform(get("/api/metrics/memory")
                        .accept(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timestamp").exists());
    }
}
