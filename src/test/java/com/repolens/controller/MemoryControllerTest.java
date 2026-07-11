package com.repolens.controller;

import com.repolens.domain.vo.AgentMemoryVO;
import com.repolens.security.PermissionService;
import com.repolens.service.AgentMemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.repolens.controller.TestAuthUtils;

class MemoryControllerTest {

    private AgentMemoryService service;
    private PermissionService permissionService;
    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        service = mock(AgentMemoryService.class);
        permissionService = mock(PermissionService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new MemoryController(service, permissionService))
                .setCustomArgumentResolvers(TestAuthUtils.fixedUserIdResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    void getMemoryReturnsListAsJson() throws Exception {
        when(permissionService.checkRepoPermission(eq(1L), eq(7L))).thenReturn(true);
        LocalDateTime now = LocalDateTime.now();
        AgentMemoryVO vo1 = AgentMemoryVO.builder()
                .id(1L)
                .content("This is a memory")
                .keywords("memory, test")
                .createdAt(now)
                .build();
        AgentMemoryVO vo2 = AgentMemoryVO.builder()
                .id(2L)
                .content("Another memory")
                .keywords("another, memory")
                .createdAt(now.minusHours(1))
                .build();
        when(service.list(eq(1L), eq(7L))).thenReturn(List.of(vo1, vo2));

        mockMvc.perform(get("/api/repos/7/memory")
                        .accept(APPLICATION_JSON)
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].content").value("This is a memory"))
                .andExpect(jsonPath("$.data[1].id").value(2));
    }

    @Test
    void deleteMemoryCallsForgetAndReturnsSuccess() throws Exception {
        when(permissionService.checkRepoPermission(eq(1L), eq(7L))).thenReturn(true);

        mockMvc.perform(delete("/api/repos/7/memory/42")
                        .accept(APPLICATION_JSON)
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(service).forget(eq(1L), eq(7L), eq(42L));
    }
}
