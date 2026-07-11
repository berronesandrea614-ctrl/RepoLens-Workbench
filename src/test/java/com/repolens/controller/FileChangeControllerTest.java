package com.repolens.controller;

import com.repolens.domain.vo.ChangeRiskVO;
import com.repolens.domain.vo.FileChangeDetailVO;
import com.repolens.domain.vo.FileChangeVO;
import com.repolens.service.ChangeRiskService;
import com.repolens.service.FileChangeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
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

import com.repolens.controller.TestAuthUtils;

class FileChangeControllerTest {

    private FileChangeService fileChangeService;
    private ChangeRiskService changeRiskService;
    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        fileChangeService = mock(FileChangeService.class);
        changeRiskService = mock(ChangeRiskService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new FileChangeController(fileChangeService, changeRiskService))
                .setCustomArgumentResolvers(TestAuthUtils.fixedUserIdResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    void listChanges_returnsDetailJsonWithStatus() throws Exception {
        when(fileChangeService.listChanges(eq(1L), eq(7L), eq(9L))).thenReturn(List.of(
                FileChangeDetailVO.builder()
                        .id(50L).filePath("src/A.java")
                        .oldContent("OLD").newContent("NEW")
                        .createdAt(LocalDateTime.now()).reverted(0).status("PROPOSED").build()));

        mockMvc.perform(get("/api/repos/7/changes").param("sessionId", "9")
                        .accept(APPLICATION_JSON).header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].id").value(50))
                .andExpect(jsonPath("$.data[0].filePath").value("src/A.java"))
                .andExpect(jsonPath("$.data[0].oldContent").value("OLD"))
                .andExpect(jsonPath("$.data[0].newContent").value("NEW"))
                .andExpect(jsonPath("$.data[0].status").value("PROPOSED"));
    }

    @Test
    void apply_invokesServiceAndReturnsChange() throws Exception {
        when(fileChangeService.apply(eq(1L), eq(7L), eq(50L), eq(false)))
                .thenReturn(FileChangeVO.builder().id(50L).filePath("src/A.java").changeId(50L).build());

        mockMvc.perform(post("/api/repos/7/changes/50/apply").header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.changeId").value(50));

        verify(fileChangeService, times(1)).apply(eq(1L), eq(7L), eq(50L), eq(false));
    }

    @Test
    void reject_invokesServiceAndReturnsChange() throws Exception {
        when(fileChangeService.reject(eq(1L), eq(7L), eq(50L)))
                .thenReturn(FileChangeVO.builder().id(50L).filePath("src/A.java").changeId(50L).build());

        mockMvc.perform(post("/api/repos/7/changes/50/reject").header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.changeId").value(50));

        verify(fileChangeService, times(1)).reject(eq(1L), eq(7L), eq(50L));
    }

    @Test
    void applyAll_invokesServiceWithSession() throws Exception {
        when(fileChangeService.applyAll(eq(1L), eq(7L), eq(9L), eq(false)))
                .thenReturn(List.of(FileChangeVO.builder().id(50L).filePath("src/A.java").changeId(50L).build()));

        mockMvc.perform(post("/api/repos/7/changes/apply-all").param("sessionId", "9").header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].changeId").value(50));

        verify(fileChangeService, times(1)).applyAll(eq(1L), eq(7L), eq(9L), eq(false));
    }

    @Test
    void rejectAll_invokesServiceWithSession() throws Exception {
        when(fileChangeService.rejectAll(eq(1L), eq(7L), eq(9L)))
                .thenReturn(List.of(FileChangeVO.builder().id(50L).filePath("src/A.java").changeId(50L).build()));

        mockMvc.perform(post("/api/repos/7/changes/reject-all").param("sessionId", "9").header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].changeId").value(50));

        verify(fileChangeService, times(1)).rejectAll(eq(1L), eq(7L), eq(9L));
    }

    @Test
    void revert_invokesServiceAndReturnsNewChange() throws Exception {
        when(fileChangeService.revert(eq(1L), eq(7L), eq(50L)))
                .thenReturn(FileChangeVO.builder().id(51L).filePath("src/A.java").changeId(51L).build());

        mockMvc.perform(post("/api/repos/7/changes/50/revert").header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.changeId").value(51));

        verify(fileChangeService, times(1)).revert(eq(1L), eq(7L), eq(50L));
    }

    // ——————————— E P1: ack param + risk endpoints ———————————

    @Test
    void apply_withAckTrue_passesAckTrueToService() throws Exception {
        when(fileChangeService.apply(eq(1L), eq(7L), eq(50L), eq(true)))
                .thenReturn(FileChangeVO.builder().id(50L).filePath("src/A.java").changeId(50L).build());

        mockMvc.perform(post("/api/repos/7/changes/50/apply").param("ack", "true").header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.changeId").value(50));

        verify(fileChangeService, times(1)).apply(eq(1L), eq(7L), eq(50L), eq(true));
    }

    @Test
    void listRisk_returnsRiskFlags() throws Exception {
        ChangeRiskVO vo = new ChangeRiskVO();
        vo.setChangeId(50L);
        vo.setSeverity("BLOCK");
        vo.setRuleCode("DELETE_FILE");
        vo.setAcknowledged(false);
        when(changeRiskService.listBySession(eq(1L), eq(7L), eq(9L))).thenReturn(List.of(vo));

        mockMvc.perform(get("/api/repos/7/changes/risk").param("sessionId", "9").header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].changeId").value(50))
                .andExpect(jsonPath("$.data[0].severity").value("BLOCK"));

        verify(changeRiskService, times(1)).listBySession(eq(1L), eq(7L), eq(9L));
    }

    @Test
    void acknowledgeRisk_invokesServiceAndReturns200() throws Exception {
        mockMvc.perform(post("/api/repos/7/changes/50/acknowledge-risk").header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(changeRiskService, times(1)).acknowledge(eq(1L), eq(7L), eq(50L));
    }
}
