package com.repolens.controller;

import com.repolens.domain.vo.FileWriteResultVO;
import com.repolens.service.RepoFileWriteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.repolens.controller.TestAuthUtils;

class FileWriteControllerTest {

    private RepoFileWriteService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        service = mock(RepoFileWriteService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new FileWriteController(service))
                .setCustomArgumentResolvers(TestAuthUtils.fixedUserIdResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    void writesFileAndReturnsBytes() throws Exception {
        when(service.writeFile(eq(1L), any()))
                .thenReturn(FileWriteResultVO.builder().filePath("src/A.java").bytes(11).build());
        mockMvc.perform(put("/api/files/content")
                        .contentType(APPLICATION_JSON).accept(APPLICATION_JSON)
                        .header("X-User-Id", "1")
                        .content("{\"repoId\":1,\"filePath\":\"src/A.java\",\"content\":\"new content\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.bytes").value(11));
    }
}
