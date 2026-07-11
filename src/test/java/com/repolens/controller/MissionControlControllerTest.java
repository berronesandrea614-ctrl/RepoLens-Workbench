package com.repolens.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.repolens.domain.vo.AgentLaneVO;
import com.repolens.domain.vo.MissionControlVO;
import com.repolens.domain.vo.ReviewItemVO;
import com.repolens.domain.vo.SummaryVO;
import com.repolens.service.MissionControlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * standaloneSetup 控制器测试（无 Spring 容器、无 DB）。
 * 验证 GET /api/repos/{repoId}/mission-control/overview 返回 200 + VO json。
 */
class MissionControlControllerTest {

    private MissionControlService missionControlService;
    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        missionControlService = mock(MissionControlService.class);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        mockMvc = MockMvcBuilders
                .standaloneSetup(new MissionControlController(missionControlService))
                .setCustomArgumentResolvers(TestAuthUtils.fixedUserIdResolver(1L))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    // ── GET /api/repos/{repoId}/mission-control/overview ─────────────────────

    @Test
    void overview_returns200AndVoJson() throws Exception {
        SummaryVO summary = SummaryVO.builder()
                .laneCount(2)
                .totalBlockRisks(3)
                .totalWarnRisks(5)
                .needsAttentionCount(1)
                .redDebtFiles(4)
                .yellowDebtFiles(7)
                .build();

        AgentLaneVO lane = new AgentLaneVO();
        lane.setLaneId(100L);
        lane.setEngine("NATIVE");
        lane.setStatus("DONE");

        ReviewItemVO reviewItem = ReviewItemVO.builder()
                .changeId(200L)
                .kind("DESTRUCTIVE")
                .severity("BLOCK")
                .reversibility("IRREVERSIBLE")
                .interrupt(true)
                .filePath("src/main/resources/schema.sql")
                .build();

        MissionControlVO vo = MissionControlVO.builder()
                .summary(summary)
                .lanes(List.of(lane))
                .reviewQueue(List.of(reviewItem))
                .build();

        when(missionControlService.overview(1L, 7L)).thenReturn(vo);

        mockMvc.perform(get("/api/repos/7/mission-control/overview")
                        .header("X-User-Id", "1")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.summary.laneCount").value(2))
                .andExpect(jsonPath("$.data.summary.totalBlockRisks").value(3))
                .andExpect(jsonPath("$.data.summary.totalWarnRisks").value(5))
                .andExpect(jsonPath("$.data.summary.needsAttentionCount").value(1))
                .andExpect(jsonPath("$.data.summary.redDebtFiles").value(4))
                .andExpect(jsonPath("$.data.summary.yellowDebtFiles").value(7))
                .andExpect(jsonPath("$.data.lanes[0].laneId").value(100))
                .andExpect(jsonPath("$.data.lanes[0].engine").value("NATIVE"))
                .andExpect(jsonPath("$.data.lanes[0].status").value("DONE"))
                .andExpect(jsonPath("$.data.reviewQueue[0].changeId").value(200))
                .andExpect(jsonPath("$.data.reviewQueue[0].kind").value("DESTRUCTIVE"))
                .andExpect(jsonPath("$.data.reviewQueue[0].interrupt").value(true));
    }
}
