package com.repolens.controller;

import com.repolens.domain.vo.TraceForwardVO;
import com.repolens.domain.vo.TraceMapVO;
import com.repolens.domain.vo.TraceReverseVO;
import com.repolens.service.ReconciliationService;
import com.repolens.service.RequirementInsightService;
import com.repolens.service.RequirementService;
import com.repolens.service.TraceabilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Feature C traceability 接口控制层契约测试（standaloneSetup，不加载 Spring 上下文）。
 *
 * <p>覆盖 4 个端点:
 * <ol>
 *   <li>GET  /api/repos/{repoId}/traceability</li>
 *   <li>POST /api/repos/{repoId}/traceability/recompute</li>
 *   <li>GET  /api/repos/{repoId}/requirements/{requirementId}/trace</li>
 *   <li>GET  /api/repos/{repoId}/symbols/{symbolId}/trace</li>
 * </ol>
 */
class TraceabilityControllerTest {

    private TraceabilityService traceabilityService;
    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        traceabilityService = mock(TraceabilityService.class);
        RequirementService requirementService = mock(RequirementService.class);
        RequirementInsightService requirementInsightService = mock(RequirementInsightService.class);
        ReconciliationService reconciliationService = mock(ReconciliationService.class);
        RequirementController controller = new RequirementController(
                requirementService, requirementInsightService, reconciliationService, traceabilityService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(TestAuthUtils.fixedUserIdResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    // ── GET /api/repos/{repoId}/traceability ────────────────────────────────

    @Test
    void traceabilityMap_returnsVO() throws Exception {
        TraceMapVO vo = TraceMapVO.builder()
                .metrics(TraceMapVO.Metrics.builder()
                        .coverage(0.8).orphanCount(1).danglingCount(2).staleCount(0).build())
                .nodes(List.of(
                        TraceMapVO.TraceNode.builder()
                                .nodeType("req").id("req-1").label("Add login").layer(null).flag(null).build(),
                        TraceMapVO.TraceNode.builder()
                                .nodeType("sym").id("sym-10").label("LoginService").layer("Service").flag(null).build()))
                .edges(List.of(
                        TraceMapVO.TraceEdge.builder()
                                .source("req-1").target("sym-10").linkType("DECLARED").confidence(1.0).status("linked").build()))
                .degraded(false)
                .build();
        when(traceabilityService.getOrComputeMap(eq(1L), eq(7L))).thenReturn(vo);

        mockMvc.perform(get("/api/repos/7/traceability")
                        .accept(APPLICATION_JSON)
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.metrics.coverage").value(0.8))
                .andExpect(jsonPath("$.data.metrics.orphanCount").value(1))
                .andExpect(jsonPath("$.data.metrics.danglingCount").value(2))
                .andExpect(jsonPath("$.data.degraded").value(false))
                .andExpect(jsonPath("$.data.nodes[0].nodeType").value("req"))
                .andExpect(jsonPath("$.data.nodes[0].id").value("req-1"))
                .andExpect(jsonPath("$.data.nodes[1].nodeType").value("sym"))
                .andExpect(jsonPath("$.data.nodes[1].layer").value("Service"))
                .andExpect(jsonPath("$.data.edges[0].linkType").value("DECLARED"))
                .andExpect(jsonPath("$.data.edges[0].source").value("req-1"))
                .andExpect(jsonPath("$.data.edges[0].target").value("sym-10"));
    }

    @Test
    void traceabilityMap_degraded_returnsFlag() throws Exception {
        TraceMapVO vo = TraceMapVO.builder()
                .metrics(TraceMapVO.Metrics.builder()
                        .coverage(0.5).orphanCount(0).danglingCount(0).staleCount(0).build())
                .nodes(List.of()).edges(List.of())
                .degraded(true)
                .build();
        when(traceabilityService.getOrComputeMap(eq(1L), eq(7L))).thenReturn(vo);

        mockMvc.perform(get("/api/repos/7/traceability")
                        .accept(APPLICATION_JSON)
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.degraded").value(true))
                .andExpect(jsonPath("$.data.metrics.coverage").value(0.5));
    }

    // ── POST /api/repos/{repoId}/traceability/recompute ──────────────────────

    @Test
    void traceabilityRecompute_invokesForcedRecomputeAndReturnsVO() throws Exception {
        TraceMapVO vo = TraceMapVO.builder()
                .metrics(TraceMapVO.Metrics.builder()
                        .coverage(1.0).orphanCount(0).danglingCount(0).staleCount(0).build())
                .nodes(List.of()).edges(List.of())
                .degraded(false)
                .build();
        when(traceabilityService.recompute(eq(1L), eq(7L))).thenReturn(vo);

        mockMvc.perform(post("/api/repos/7/traceability/recompute")
                        .accept(APPLICATION_JSON)
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.metrics.coverage").value(1.0))
                .andExpect(jsonPath("$.data.degraded").value(false));

        verify(traceabilityService, times(1)).recompute(eq(1L), eq(7L));
    }

    // ── GET /api/repos/{repoId}/requirements/{requirementId}/trace ───────────

    @Test
    void forwardTrace_returnsLinks() throws Exception {
        TraceForwardVO.TraceLink link = TraceForwardVO.TraceLink.builder()
                .symbolId(10L).filePath("src/LoginService.java").startLine(25)
                .linkType("DECLARED").confidence(1.0).status("LINKED")
                .symbolName("LoginService.login").layer("Service").build();
        TraceForwardVO vo = TraceForwardVO.builder()
                .requirementId(5L).title("Add login").coverage(1.0)
                .links(List.of(link)).build();
        when(traceabilityService.forwardTrace(eq(1L), eq(7L), eq(5L))).thenReturn(vo);

        mockMvc.perform(get("/api/repos/7/requirements/5/trace")
                        .accept(APPLICATION_JSON)
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.requirementId").value(5))
                .andExpect(jsonPath("$.data.title").value("Add login"))
                .andExpect(jsonPath("$.data.coverage").value(1.0))
                .andExpect(jsonPath("$.data.links[0].symbolId").value(10))
                .andExpect(jsonPath("$.data.links[0].filePath").value("src/LoginService.java"))
                .andExpect(jsonPath("$.data.links[0].startLine").value(25))
                .andExpect(jsonPath("$.data.links[0].linkType").value("DECLARED"))
                .andExpect(jsonPath("$.data.links[0].layer").value("Service"));
    }

    @Test
    void forwardTrace_danglingRequirement_returnsEmptyLinks() throws Exception {
        TraceForwardVO vo = TraceForwardVO.builder()
                .requirementId(9L).title("Orphan req").coverage(0.0)
                .links(List.of()).build();
        when(traceabilityService.forwardTrace(eq(1L), eq(7L), eq(9L))).thenReturn(vo);

        mockMvc.perform(get("/api/repos/7/requirements/9/trace")
                        .accept(APPLICATION_JSON)
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.coverage").value(0.0))
                .andExpect(jsonPath("$.data.links").isArray())
                .andExpect(jsonPath("$.data.links").isEmpty());
    }

    // ── GET /api/repos/{repoId}/symbols/{symbolId}/trace ────────────────────

    @Test
    void reverseTrace_returnsReqs() throws Exception {
        TraceReverseVO.ReqLink req = TraceReverseVO.ReqLink.builder()
                .requirementId(5L).title("Add login")
                .linkType("DECLARED").confidence(1.0).status("LINKED").build();
        TraceReverseVO vo = TraceReverseVO.builder()
                .symbolId(10L).symbolName("LoginService.login").layer("Service")
                .requirements(List.of(req)).build();
        when(traceabilityService.reverseTrace(eq(1L), eq(7L), eq(10L))).thenReturn(vo);

        mockMvc.perform(get("/api/repos/7/symbols/10/trace")
                        .accept(APPLICATION_JSON)
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.symbolId").value(10))
                .andExpect(jsonPath("$.data.symbolName").value("LoginService.login"))
                .andExpect(jsonPath("$.data.layer").value("Service"))
                .andExpect(jsonPath("$.data.requirements[0].requirementId").value(5))
                .andExpect(jsonPath("$.data.requirements[0].title").value("Add login"))
                .andExpect(jsonPath("$.data.requirements[0].linkType").value("DECLARED"))
                .andExpect(jsonPath("$.data.requirements[0].confidence").value(1.0));
    }
}
