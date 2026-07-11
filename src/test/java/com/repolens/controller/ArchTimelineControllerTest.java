package com.repolens.controller;

import com.repolens.domain.vo.CodeGraphVO;
import com.repolens.domain.vo.FrameVO;
import com.repolens.domain.vo.GraphNodeVO;
import com.repolens.domain.vo.TimelineVO;
import com.repolens.service.ArchTimelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ArchTimelineControllerTest {

    private ArchTimelineService archTimelineService;
    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        archTimelineService = mock(ArchTimelineService.class);
        ArchTimelineController controller = new ArchTimelineController(archTimelineService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(TestAuthUtils.fixedUserIdResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    void timeline_returnsTimelineVoJson() throws Exception {
        TimelineVO timeline = TimelineVO.builder()
                .frames(List.of(
                        FrameVO.builder().frameIndex(0).agentRunId(1L).createdAt("2026-01-01T00:00:00Z")
                                .changedFilePaths(List.of("src/main.java")).changedFileCount(1).touchedSymbolCount(5).build(),
                        FrameVO.builder().frameIndex(1).agentRunId(2L).createdAt("2026-01-02T00:00:00Z")
                                .changedFilePaths(List.of("src/service.java")).changedFileCount(1).touchedSymbolCount(3).build()
                ))
                .frameCount(2)
                .historyLimited(true)
                .build();
        when(archTimelineService.getTimeline(eq(1L), eq(7L))).thenReturn(timeline);

        mockMvc.perform(get("/api/repos/7/timeline")
                        .accept(APPLICATION_JSON)
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.frameCount").value(2))
                .andExpect(jsonPath("$.data.historyLimited").value(true))
                .andExpect(jsonPath("$.data.frames[0].frameIndex").value(0))
                .andExpect(jsonPath("$.data.frames[0].agentRunId").value(1))
                .andExpect(jsonPath("$.data.frames[1].frameIndex").value(1));
    }

    @Test
    void frameGraph_returnsCodeGraphVoJson() throws Exception {
        CodeGraphVO graph = CodeGraphVO.builder()
                .rootId("1")
                .nodes(List.of(
                        GraphNodeVO.builder().id("1").label("UserService.save")
                                .symbolType("CLASS").resolved(true).build(),
                        GraphNodeVO.builder().id("2").label("UserController.create")
                                .symbolType("API").resolved(true).build()
                ))
                .edges(List.of())
                .nodeCount(2)
                .edgeCount(0)
                .truncated(false)
                .build();
        when(archTimelineService.getFrameGraph(eq(1L), eq(7L), eq(0))).thenReturn(graph);

        mockMvc.perform(get("/api/repos/7/timeline/0/graph")
                        .accept(APPLICATION_JSON)
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.rootId").value("1"))
                .andExpect(jsonPath("$.data.nodeCount").value(2))
                .andExpect(jsonPath("$.data.edgeCount").value(0))
                .andExpect(jsonPath("$.data.truncated").value(false))
                .andExpect(jsonPath("$.data.nodes[0].id").value("1"))
                .andExpect(jsonPath("$.data.nodes[0].label").value("UserService.save"))
                .andExpect(jsonPath("$.data.nodes[1].symbolType").value("API"));
    }
}
