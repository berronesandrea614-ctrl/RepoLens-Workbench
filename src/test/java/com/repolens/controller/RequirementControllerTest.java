package com.repolens.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.domain.vo.CodeGraphVO;
import com.repolens.domain.vo.GraphNodeVO;
import com.repolens.domain.vo.ReconciliationVO;
import com.repolens.domain.vo.RequirementVO;
import com.repolens.service.ReconciliationService;
import com.repolens.service.RequirementInsightService;
import com.repolens.service.RequirementService;
import com.repolens.service.TraceabilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.repolens.controller.TestAuthUtils;

/**
 * 需求接口控制层单测（standaloneSetup + mock RequirementService，不加载 Spring 上下文）。
 */
class RequirementControllerTest {

    private RequirementService requirementService;
    private ReconciliationService reconciliationService;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setup() {
        requirementService = mock(RequirementService.class);
        RequirementInsightService requirementInsightService = mock(RequirementInsightService.class);
        reconciliationService = mock(ReconciliationService.class);
        TraceabilityService traceabilityService = mock(TraceabilityService.class);
        RequirementController controller = new RequirementController(
                requirementService, requirementInsightService, reconciliationService, traceabilityService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(TestAuthUtils.fixedUserIdResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    void list_returnsRequirementJson() throws Exception {
        when(requirementService.list(eq(1L), eq(7L))).thenReturn(List.of(
                RequirementVO.builder().id(10L).title("登录流程").status("SUMMARIZED").fileCount(2).build()));

        mockMvc.perform(get("/api/repos/7/requirements").accept(APPLICATION_JSON).header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].id").value(10))
                .andExpect(jsonPath("$.data[0].title").value("登录流程"))
                .andExpect(jsonPath("$.data[0].fileCount").value(2));
    }

    @Test
    void graph_returnsCodeGraphJson() throws Exception {
        CodeGraphVO g = CodeGraphVO.builder()
                .rootId("100")
                .nodes(List.of(GraphNodeVO.builder().id("100").label("A.m").build()))
                .edges(List.of())
                .nodeCount(1).edgeCount(0).truncated(false).build();
        when(requirementService.requirementGraph(eq(1L), eq(7L), eq(10L))).thenReturn(g);

        mockMvc.perform(get("/api/repos/7/requirements/10/graph").accept(APPLICATION_JSON).header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.rootId").value("100"))
                .andExpect(jsonPath("$.data.nodes[0].id").value("100"))
                .andExpect(jsonPath("$.data.nodeCount").value(1));
    }

    @Test
    void delete_invokesServiceDelete() throws Exception {
        mockMvc.perform(delete("/api/repos/7/requirements/10").header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(requirementService, times(1)).delete(eq(1L), eq(7L), eq(10L));
    }

    // ── POST /api/repos/{repoId}/external-changes/summarize ──────────────────

    @Test
    void externalChangesSummarize_returnsRequirementId() throws Exception {
        RequirementVO vo = RequirementVO.builder()
                .id(42L).title("外部改动归纳").summary("..").status("SUMMARIZED").source("external").fileCount(2).build();
        when(requirementService.summarizeExternal(eq(1L), eq(7L), any(), any(), any()))
                .thenReturn(Optional.of(vo));

        String body = objectMapper.writeValueAsString(Map.of(
                "changedFiles", List.of("src/Main.java", "src/Util.java"),
                "realDir", "/tmp/myproject"));

        mockMvc.perform(post("/api/repos/7/external-changes/summarize")
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(42));
    }

    @Test
    void externalChangesSummarize_noExtraction_returnsNullData() throws Exception {
        when(requirementService.summarizeExternal(eq(1L), eq(7L), any(), any(), any()))
                .thenReturn(Optional.empty());

        String body = objectMapper.writeValueAsString(Map.of(
                "changedFiles", List.of("src/Main.java"),
                "realDir", "/tmp/myproject"));

        mockMvc.perform(post("/api/repos/7/external-changes/summarize")
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void externalChangesSummarize_emptyChangedFiles_returnsNullData() throws Exception {
        when(requirementService.summarizeExternal(eq(1L), eq(7L), any(), any(), any()))
                .thenReturn(Optional.empty());

        String body = objectMapper.writeValueAsString(Map.of("changedFiles", List.of()));

        mockMvc.perform(post("/api/repos/7/external-changes/summarize")
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    // ── GET /reconciliation ─────────────────────────────────────────────────

    @Test
    void reconciliation_returnsVoJson() throws Exception {
        ReconciliationVO vo = ReconciliationVO.builder()
                .planned(true).degrade(false)
                .summary(ReconciliationVO.Summary.builder()
                        .coverage(0.8).fidelity(0.75).offPlanCount(2)
                        .violationCount(0).trustFlag("FABRICATED")
                        .humanLine("计划5文件落实4(80%)｜实际6改动有2处计划外").build())
                .items(List.of())
                .offPlan(List.of(ReconciliationVO.OffPlanChange.builder()
                        .filePath("src/config/SecurityConfig.java")
                        .classification("SILENT_ADD").build()))
                .selfReport(ReconciliationVO.SelfReport.builder()
                        .claimedVerified(true).trustFlag("FABRICATED")
                        .checks(List.of(ReconciliationVO.SelfReportCheck.builder()
                                .type("FABRICATED_VERIFICATION").severity("RED")
                                .detail("无 runVerification").build()))
                        .build())
                .build();

        when(reconciliationService.getOrCompute(eq(1L), eq(7L), eq(10L))).thenReturn(vo);

        mockMvc.perform(get("/api/repos/7/requirements/10/reconciliation")
                        .accept(APPLICATION_JSON).header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.planned").value(true))
                .andExpect(jsonPath("$.data.summary.trustFlag").value("FABRICATED"))
                .andExpect(jsonPath("$.data.offPlan[0].classification").value("SILENT_ADD"))
                .andExpect(jsonPath("$.data.selfReport.checks[0].type").value("FABRICATED_VERIFICATION"));
    }

    // ── Constraint violations in reconciliation response ──────────────────

    @Test
    void reconciliation_withConstraintViolations_returnsViolationsInJson() throws Exception {
        ReconciliationVO vo = ReconciliationVO.builder()
                .planned(true).degrade(false)
                .summary(ReconciliationVO.Summary.builder()
                        .coverage(0.9).fidelity(0.85).offPlanCount(0)
                        .violationCount(1).trustFlag("OK")
                        .humanLine("计划5文件落实4(80%)").build())
                .items(List.of()).offPlan(List.of())
                .selfReport(ReconciliationVO.SelfReport.builder()
                        .trustFlag("OK").checks(List.of()).build())
                .violations(List.of(ReconciliationVO.ConstraintViolation.builder()
                        .ruleType("NO_NEW_DEP")
                        .rawText("禁止新增 Maven 依赖")
                        .matchedFiles(List.of("pom.xml"))
                        .severity("BLOCK")
                        .build()))
                .build();

        when(reconciliationService.getOrCompute(eq(1L), eq(7L), eq(10L))).thenReturn(vo);

        mockMvc.perform(get("/api/repos/7/requirements/10/reconciliation")
                        .accept(APPLICATION_JSON).header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.summary.violationCount").value(1))
                .andExpect(jsonPath("$.data.violations[0].ruleType").value("NO_NEW_DEP"))
                .andExpect(jsonPath("$.data.violations[0].severity").value("BLOCK"))
                .andExpect(jsonPath("$.data.violations[0].matchedFiles[0]").value("pom.xml"));
    }

    // ── POST /reconciliation/recompute ──────────────────────────────────────

    @Test
    void reconciliationRecompute_invokesRecomputeAndReturnsVO() throws Exception {
        ReconciliationVO vo = ReconciliationVO.builder()
                .planned(true).degrade(false)
                .summary(ReconciliationVO.Summary.builder()
                        .coverage(1.0).fidelity(1.0).offPlanCount(0)
                        .violationCount(0).trustFlag("OK").humanLine("ok").build())
                .items(List.of()).offPlan(List.of())
                .selfReport(ReconciliationVO.SelfReport.builder()
                        .trustFlag("OK").checks(List.of()).build())
                .build();

        when(reconciliationService.recompute(eq(1L), eq(7L), eq(10L))).thenReturn(vo);

        mockMvc.perform(post("/api/repos/7/requirements/10/reconciliation/recompute")
                        .accept(APPLICATION_JSON).header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.summary.trustFlag").value("OK"))
                .andExpect(jsonPath("$.data.summary.coverage").value(1.0));

        verify(reconciliationService, times(1)).recompute(eq(1L), eq(7L), eq(10L));
    }
}
