package com.repolens.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.domain.vo.BranchGraphVO;
import com.repolens.domain.vo.BranchMetricsVO;
import com.repolens.domain.vo.BranchNodeVO;
import com.repolens.domain.vo.FileChangeVO;
import com.repolens.service.SolutionBranchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SolutionBranchController standaloneSetup 集成测试。
 * 不启动 Spring 容器，不调用真实 service（全 mock），只验证端点路由、参数绑定和响应结构。
 */
class SolutionBranchControllerTest {

    private SolutionBranchService solutionBranchService;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setup() {
        solutionBranchService = mock(SolutionBranchService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new SolutionBranchController(solutionBranchService))
                .setCustomArgumentResolvers(TestAuthUtils.fixedUserIdResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    // ================================================================
    // helpers
    // ================================================================

    private BranchGraphVO sampleGraph(Long sessionId) {
        BranchMetricsVO m0 = new BranchMetricsVO();
        m0.setFilesChanged(2);
        m0.setBlastRadiusSize(3);
        m0.setDebtDelta(0);
        m0.setConfidence(0.7);
        m0.setVerified(false);

        BranchNodeVO n0 = new BranchNodeVO();
        n0.setId(1L);
        n0.setBranchId("v0");
        n0.setVariantIndex(0);
        n0.setStatus("READY");
        n0.setStrategyHint("最小改动");
        n0.setMetrics(m0);
        n0.setDegraded(true);

        BranchNodeVO n1 = new BranchNodeVO();
        n1.setId(2L);
        n1.setBranchId("v1");
        n1.setVariantIndex(1);
        n1.setStatus("READY");
        n1.setStrategyHint("重构式");
        n1.setMetrics(m0);
        n1.setDegraded(true);

        BranchGraphVO vo = new BranchGraphVO();
        vo.setSessionId(sessionId);
        vo.setQuestion("how to fix bug");
        vo.setNodes(List.of(n0, n1));
        return vo;
    }

    // ================================================================
    // POST /fanout
    // ================================================================

    @Test
    void fanout_returns200WithBranchGraph() throws Exception {
        BranchGraphVO graph = sampleGraph(9L);
        when(solutionBranchService.fanout(anyLong(), anyLong(), anyLong(), any(), anyInt(), any()))
                .thenReturn(graph);

        String body = objectMapper.writeValueAsString(
                java.util.Map.of(
                        "sessionId", 9,
                        "question", "how to fix bug",
                        "variantCount", 2,
                        "strategies", List.of("最小改动", "重构式")));

        mockMvc.perform(post("/api/repos/7/branches/fanout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.sessionId").value(9))
                .andExpect(jsonPath("$.data.question").value("how to fix bug"))
                .andExpect(jsonPath("$.data.nodes").isArray())
                .andExpect(jsonPath("$.data.nodes.length()").value(2))
                .andExpect(jsonPath("$.data.nodes[0].branchId").value("v0"))
                .andExpect(jsonPath("$.data.nodes[0].status").value("READY"))
                .andExpect(jsonPath("$.data.nodes[0].degraded").value(true))
                .andExpect(jsonPath("$.data.nodes[0].metrics.filesChanged").value(2));

        verify(solutionBranchService).fanout(eq(1L), eq(7L), eq(9L), eq("how to fix bug"), eq(2), any());
    }

    @Test
    void fanout_defaultVariantCount_usedFromRequestBody() throws Exception {
        when(solutionBranchService.fanout(anyLong(), anyLong(), anyLong(), any(), anyInt(), any()))
                .thenReturn(sampleGraph(9L));

        // variantCount 默认值 3（从 FanoutReq.variantCount=3）
        String body = """
                {"sessionId":9,"question":"fix me"}
                """;

        mockMvc.perform(post("/api/repos/7/branches/fanout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(solutionBranchService).fanout(eq(1L), eq(7L), eq(9L), eq("fix me"), eq(3), any());
    }

    // ================================================================
    // GET /?sessionId=
    // ================================================================

    @Test
    void graph_returns200WithBranchGraph() throws Exception {
        when(solutionBranchService.getBranchGraph(eq(1L), eq(7L), eq(9L)))
                .thenReturn(sampleGraph(9L));

        mockMvc.perform(get("/api/repos/7/branches")
                        .param("sessionId", "9")
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.sessionId").value(9))
                .andExpect(jsonPath("$.data.nodes.length()").value(2))
                .andExpect(jsonPath("$.data.nodes[1].branchId").value("v1"));

        verify(solutionBranchService).getBranchGraph(eq(1L), eq(7L), eq(9L));
    }

    // ================================================================
    // POST /{branchId}/select
    // ================================================================

    @Test
    void select_returns200WithAppliedChanges() throws Exception {
        FileChangeVO change = FileChangeVO.builder()
                .id(50L).changeId(50L).filePath("src/A.java").build();
        when(solutionBranchService.select(eq(1L), eq(7L), eq(9L), eq("v0"), eq(false)))
                .thenReturn(List.of(change));

        String body = objectMapper.writeValueAsString(
                java.util.Map.of("sessionId", 9, "ack", false));

        mockMvc.perform(post("/api/repos/7/branches/v0/select")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].filePath").value("src/A.java"))
                .andExpect(jsonPath("$.data[0].changeId").value(50));

        verify(solutionBranchService).select(eq(1L), eq(7L), eq(9L), eq("v0"), eq(false));
    }

    @Test
    void select_withAckTrue_passesAckTrueToService() throws Exception {
        when(solutionBranchService.select(eq(1L), eq(7L), eq(9L), eq("v1"), eq(true)))
                .thenReturn(List.of());

        String body = objectMapper.writeValueAsString(
                java.util.Map.of("sessionId", 9, "ack", true));

        mockMvc.perform(post("/api/repos/7/branches/v1/select")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(solutionBranchService).select(eq(1L), eq(7L), eq(9L), eq("v1"), eq(true));
    }
}
