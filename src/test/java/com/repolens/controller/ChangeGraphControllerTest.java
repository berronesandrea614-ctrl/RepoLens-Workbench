package com.repolens.controller;

import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.common.exception.GlobalExceptionHandler;
import com.repolens.domain.vo.BlastSubgraphVO;
import com.repolens.domain.vo.ChangedFileVO;
import com.repolens.domain.vo.ChangeGraphVO;
import com.repolens.domain.vo.GraphEdgeVO;
import com.repolens.domain.vo.GraphNodeVO;
import com.repolens.service.ChangeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.repolens.controller.TestAuthUtils;

/**
 * ChangeGraphController 单测（standaloneSetup + mock service，不加载 Spring 上下文）。
 * 覆盖：权限拒绝、空改动、正常图、截断标志。
 */
class ChangeGraphControllerTest {

    private ChangeGraphService changeGraphService;
    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        changeGraphService = mock(ChangeGraphService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ChangeGraphController(changeGraphService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .setCustomArgumentResolvers(TestAuthUtils.fixedUserIdResolver())
                .build();
    }

    @Test
    void changeGraph_forbiddenWhenNoPermission() throws Exception {
        when(changeGraphService.getChangeGraph(eq(1L), eq(7L), eq(100L)))
                .thenThrow(new BizException(ErrorCode.FORBIDDEN, "No permission"));

        mockMvc.perform(get("/api/repos/7/agent-runs/100/change-graph")
                        .accept(APPLICATION_JSON)
                        .header("X-User-Id", "1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    void changeGraph_notFoundWhenRunMissing() throws Exception {
        when(changeGraphService.getChangeGraph(eq(1L), eq(7L), eq(999L)))
                .thenThrow(new BizException(ErrorCode.NOT_FOUND, "Agent run not found: 999"));

        mockMvc.perform(get("/api/repos/7/agent-runs/999/change-graph")
                        .accept(APPLICATION_JSON)
                        .header("X-User-Id", "1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void changeGraph_returnsEmptyGraphWhenNoFileChanges() throws Exception {
        ChangeGraphVO empty = ChangeGraphVO.builder()
                .changedFiles(Collections.emptyList())
                .changedSymbols(Collections.emptyList())
                .upstream(BlastSubgraphVO.builder()
                        .nodes(Collections.emptyList())
                        .edges(Collections.emptyList())
                        .build())
                .downstream(BlastSubgraphVO.builder()
                        .nodes(Collections.emptyList())
                        .edges(Collections.emptyList())
                        .build())
                .truncated(false)
                .build();
        when(changeGraphService.getChangeGraph(eq(1L), eq(7L), eq(100L))).thenReturn(empty);

        mockMvc.perform(get("/api/repos/7/agent-runs/100/change-graph")
                        .accept(APPLICATION_JSON)
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.changedFiles").isEmpty())
                .andExpect(jsonPath("$.data.truncated").value(false));
    }

    @Test
    void changeGraph_returnsPopulatedGraph() throws Exception {
        GraphNodeVO changedNode = GraphNodeVO.builder()
                .id("42").label("UserService.save").changeType("MODIFIED")
                .filePath("src/UserService.java").resolved(true).build();
        GraphNodeVO upNode = GraphNodeVO.builder()
                .id("10").label("UserController.create").resolved(true).build();
        GraphEdgeVO edge = GraphEdgeVO.builder()
                .id("10->42:CALL").source("10").target("42").relationType("CALL").confidence(0.95).build();

        ChangeGraphVO graph = ChangeGraphVO.builder()
                .changedFiles(List.of(ChangedFileVO.builder()
                        .filePath("src/UserService.java")
                        .changeStatus("APPLIED")
                        .changeLogId(55L)
                        .build()))
                .changedSymbols(List.of(changedNode))
                .upstream(BlastSubgraphVO.builder()
                        .nodes(List.of(upNode))
                        .edges(List.of(edge))
                        .build())
                .downstream(BlastSubgraphVO.builder()
                        .nodes(Collections.emptyList())
                        .edges(Collections.emptyList())
                        .build())
                .truncated(false)
                .build();
        when(changeGraphService.getChangeGraph(eq(1L), eq(7L), eq(100L))).thenReturn(graph);

        mockMvc.perform(get("/api/repos/7/agent-runs/100/change-graph")
                        .accept(APPLICATION_JSON)
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.changedFiles[0].filePath").value("src/UserService.java"))
                .andExpect(jsonPath("$.data.changedFiles[0].changeStatus").value("APPLIED"))
                .andExpect(jsonPath("$.data.changedFiles[0].changeLogId").value(55))
                .andExpect(jsonPath("$.data.changedSymbols[0].id").value("42"))
                .andExpect(jsonPath("$.data.changedSymbols[0].changeType").value("MODIFIED"))
                .andExpect(jsonPath("$.data.upstream.nodes[0].id").value("10"))
                .andExpect(jsonPath("$.data.upstream.edges[0].id").value("10->42:CALL"))
                .andExpect(jsonPath("$.data.truncated").value(false));
    }

    @Test
    void changeGraph_truncatedFlagPropagated() throws Exception {
        ChangeGraphVO truncatedGraph = ChangeGraphVO.builder()
                .changedFiles(List.of(ChangedFileVO.builder()
                        .filePath("src/BigService.java").changeStatus("APPLIED").changeLogId(1L).build()))
                .changedSymbols(Collections.emptyList())
                .upstream(BlastSubgraphVO.builder()
                        .nodes(Collections.emptyList()).edges(Collections.emptyList()).build())
                .downstream(BlastSubgraphVO.builder()
                        .nodes(Collections.emptyList()).edges(Collections.emptyList()).build())
                .truncated(true)
                .build();
        when(changeGraphService.getChangeGraph(eq(1L), eq(7L), eq(100L))).thenReturn(truncatedGraph);

        mockMvc.perform(get("/api/repos/7/agent-runs/100/change-graph")
                        .accept(APPLICATION_JSON)
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.truncated").value(true));
    }
}
