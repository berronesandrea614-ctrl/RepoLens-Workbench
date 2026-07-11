package com.repolens.controller;

import com.repolens.domain.vo.SearchMatchVO;
import com.repolens.domain.vo.SearchResultVO;
import com.repolens.service.RepoSearchService;
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

import com.repolens.controller.TestAuthUtils;

class SearchControllerTest {

    private RepoSearchService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        service = mock(RepoSearchService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new SearchController(service))
                .setCustomArgumentResolvers(TestAuthUtils.fixedUserIdResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    void returnsMatchesJson() throws Exception {
        SearchResultVO vo = SearchResultVO.builder().query("getUser")
                .matches(List.of(SearchMatchVO.builder()
                        .filePath("src/A.java").line(2).lineContent("  getUser()").startCol(2).build()))
                .matchCount(1).truncated(false).offset(0).limit(100).hasMore(false).build();
        when(service.search(eq(1L), eq(7L), eq("getUser"), eq(false), eq(0), eq(100))).thenReturn(vo);

        mockMvc.perform(get("/api/repos/7/search")
                        .param("q", "getUser").accept(APPLICATION_JSON).header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.matchCount").value(1))
                .andExpect(jsonPath("$.data.matches[0].line").value(2))
                .andExpect(jsonPath("$.data.offset").value(0))
                .andExpect(jsonPath("$.data.limit").value(100))
                .andExpect(jsonPath("$.data.hasMore").value(false));
    }

    @Test
    void acceptsOffsetAndLimitParams() throws Exception {
        SearchResultVO vo = SearchResultVO.builder().query("getUser")
                .matches(List.of())
                .matchCount(500).truncated(true).offset(100).limit(50).hasMore(true).build();
        when(service.search(eq(1L), eq(7L), eq("getUser"), eq(false), eq(100), eq(50))).thenReturn(vo);

        mockMvc.perform(get("/api/repos/7/search")
                        .param("q", "getUser").param("offset", "100").param("limit", "50")
                        .accept(APPLICATION_JSON).header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.offset").value(100))
                .andExpect(jsonPath("$.data.limit").value(50))
                .andExpect(jsonPath("$.data.hasMore").value(true));
    }
}
