package com.repolens.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.common.exception.GlobalExceptionHandler;
import com.repolens.domain.vo.DependencyCheckVO;
import com.repolens.security.PermissionService;
import com.repolens.service.DependencyCheckService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DependencyCheckController 契约测试（standaloneSetup，无 Spring 上下文）：
 * 1. permission reject → 403 forbidden。
 * 2. POST with changeIds → calls checkByChangeIds, returns results。
 * 3. POST with sessionId → calls checkBySession。
 * 4. GET by sessionId → calls queryBySession, returns results。
 */
class DependencyCheckControllerTest {

    private DependencyCheckService dependencyCheckService;
    private PermissionService permissionService;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setup() {
        dependencyCheckService = mock(DependencyCheckService.class);
        permissionService = mock(PermissionService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new DependencyCheckController(dependencyCheckService, permissionService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(TestAuthUtils.fixedUserIdResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    private DependencyCheckVO sampleVO(String verdict) {
        return DependencyCheckVO.builder()
                .id(1L)
                .repoId(7L)
                .sessionId(9L)
                .changeId(50L)
                .filePath("requirements.txt")
                .ecosystem("pypi")
                .packageName("requets")
                .source("MANIFEST")
                .verdict(verdict)
                .detailJson("{\"suggestion\":\"requests\",\"distance\":1}")
                .checkedAt(LocalDateTime.now())
                .build();
    }

    // ──────────────── permission reject ──────────────────────────────────────

    @Test
    void post_permissionDenied_returns403() throws Exception {
        when(permissionService.checkRepoPermission(eq(1L), eq(7L))).thenReturn(false);

        mockMvc.perform(post("/api/repos/7/dependency-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changeIds\":[50]}")
                        .header("X-User-Id", "1"))
                .andExpect(status().isForbidden());

        verify(dependencyCheckService, never()).checkByChangeIds(any(), any(), any());
    }

    @Test
    void get_permissionDenied_returns403() throws Exception {
        when(permissionService.checkRepoPermission(eq(1L), eq(7L))).thenReturn(false);

        mockMvc.perform(get("/api/repos/7/dependency-check")
                        .param("sessionId", "9")
                        .header("X-User-Id", "1"))
                .andExpect(status().isForbidden());

        verify(dependencyCheckService, never()).queryBySession(any(), any());
    }

    // ──────────────── POST with changeIds ────────────────────────────────────

    @Test
    void post_withChangeIds_callsCheckAndReturnsResults() throws Exception {
        when(permissionService.checkRepoPermission(eq(1L), eq(7L))).thenReturn(true);
        when(dependencyCheckService.checkByChangeIds(eq(7L), any(), eq(List.of(50L))))
                .thenReturn(List.of(sampleVO("TYPOSQUAT")));

        mockMvc.perform(post("/api/repos/7/dependency-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changeIds\":[50]}")
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].verdict").value("TYPOSQUAT"))
                .andExpect(jsonPath("$.data[0].packageName").value("requets"))
                .andExpect(jsonPath("$.data[0].ecosystem").value("pypi"));

        verify(dependencyCheckService).checkByChangeIds(eq(7L), any(), eq(List.of(50L)));
    }

    @Test
    void post_withMultipleChangeIds_returnsAllResults() throws Exception {
        when(permissionService.checkRepoPermission(eq(1L), eq(7L))).thenReturn(true);
        when(dependencyCheckService.checkByChangeIds(eq(7L), any(), eq(List.of(50L, 51L))))
                .thenReturn(List.of(sampleVO("OK"), sampleVO("NOT_FOUND")));

        mockMvc.perform(post("/api/repos/7/dependency-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changeIds\":[50,51]}")
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    // ──────────────── POST with sessionId ────────────────────────────────────

    @Test
    void post_withSessionId_callsCheckBySession() throws Exception {
        when(permissionService.checkRepoPermission(eq(1L), eq(7L))).thenReturn(true);
        when(dependencyCheckService.checkBySession(eq(7L), eq(9L)))
                .thenReturn(List.of(sampleVO("NOT_FOUND")));

        mockMvc.perform(post("/api/repos/7/dependency-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":9}")
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].verdict").value("NOT_FOUND"));

        verify(dependencyCheckService).checkBySession(eq(7L), eq(9L));
        verify(dependencyCheckService, never()).checkByChangeIds(any(), any(), any());
    }

    @Test
    void post_withEmptyBody_returnsEmptyResults() throws Exception {
        when(permissionService.checkRepoPermission(eq(1L), eq(7L))).thenReturn(true);

        mockMvc.perform(post("/api/repos/7/dependency-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ──────────────── GET by sessionId ───────────────────────────────────────

    @Test
    void get_bySession_returnsExistingResults() throws Exception {
        when(permissionService.checkRepoPermission(eq(1L), eq(7L))).thenReturn(true);
        when(dependencyCheckService.queryBySession(eq(7L), eq(9L)))
                .thenReturn(List.of(sampleVO("OK"), sampleVO("TYPOSQUAT")));

        mockMvc.perform(get("/api/repos/7/dependency-check")
                        .param("sessionId", "9")
                        .accept(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].verdict").value("OK"))
                .andExpect(jsonPath("$.data[1].verdict").value("TYPOSQUAT"));

        verify(dependencyCheckService).queryBySession(eq(7L), eq(9L));
    }

    @Test
    void get_bySession_emptyResults() throws Exception {
        when(permissionService.checkRepoPermission(eq(1L), eq(7L))).thenReturn(true);
        when(dependencyCheckService.queryBySession(eq(7L), eq(9L))).thenReturn(List.of());

        mockMvc.perform(get("/api/repos/7/dependency-check")
                        .param("sessionId", "9")
                        .accept(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }
}
