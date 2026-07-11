package com.repolens.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.repolens.domain.vo.AdrVO;
import com.repolens.service.AdrService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AdrController standaloneSetup contract tests.
 * Covers generate / list / get / accept — 200 OK scenarios.
 */
class AdrControllerTest {

    private AdrService adrService;
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @BeforeEach
    void setup() {
        adrService = mock(AdrService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AdrController(adrService))
                .setCustomArgumentResolvers(TestAuthUtils.fixedUserIdResolver(1L))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private AdrVO buildVO(String status) {
        AdrVO vo = new AdrVO();
        vo.setId(1L);
        vo.setRepoId(10L);
        vo.setTitle("Use MyBatis-Plus for ORM");
        vo.setStatus(status);
        vo.setContext("We need a persistence layer");
        vo.setDecision("Use MyBatis-Plus");
        vo.setConsequences("Easy to use, less boilerplate");
        vo.setDrivers(List.of("Performance", "Simplicity"));
        vo.setOptions(List.of("JPA", "MyBatis-Plus"));
        vo.setSourceType("REQUIREMENT");
        vo.setSourceId(5L);
        vo.setDegraded(0);
        return vo;
    }

    // ── POST /generate ────────────────────────────────────────────────────────

    @Test
    void generate_returns200WithProposedVO() throws Exception {
        AdrVO vo = buildVO("PROPOSED");
        when(adrService.generateFromRequirement(eq(1L), eq(10L), eq(5L))).thenReturn(vo);

        mockMvc.perform(post("/api/repos/10/adrs/generate")
                        .accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirementId\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("PROPOSED"))
                .andExpect(jsonPath("$.data.title").value("Use MyBatis-Plus for ORM"))
                .andExpect(jsonPath("$.data.sourceType").value("REQUIREMENT"));
    }

    // ── GET /list ─────────────────────────────────────────────────────────────

    @Test
    void list_returns200WithArray() throws Exception {
        when(adrService.list(eq(1L), eq(10L))).thenReturn(List.of(buildVO("PROPOSED")));

        mockMvc.perform(get("/api/repos/10/adrs")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].title").value("Use MyBatis-Plus for ORM"))
                .andExpect(jsonPath("$.data[0].status").value("PROPOSED"));
    }

    // ── GET /{adrId} ──────────────────────────────────────────────────────────

    @Test
    void get_returns200() throws Exception {
        when(adrService.get(eq(1L), eq(10L), eq(1L))).thenReturn(buildVO("PROPOSED"));

        mockMvc.perform(get("/api/repos/10/adrs/1")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.context").value("We need a persistence layer"));
    }

    // ── POST /{adrId}/accept ──────────────────────────────────────────────────

    @Test
    void accept_returns200WithAcceptedVO() throws Exception {
        AdrVO vo = buildVO("ACCEPTED");
        vo.setNumber(1);
        vo.setFilePath("docs/adr/0001.md");
        when(adrService.accept(eq(1L), eq(10L), eq(1L))).thenReturn(vo);

        mockMvc.perform(post("/api/repos/10/adrs/1/accept")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.number").value(1))
                .andExpect(jsonPath("$.data.filePath").value("docs/adr/0001.md"));
    }

    // ── POST /{adrId}/supersede ───────────────────────────────────────────────

    @Test
    void supersede_returns200WithSupersededVO() throws Exception {
        AdrVO vo = buildVO("SUPERSEDED");
        vo.setSupersededBy(2L);
        when(adrService.supersede(eq(1L), eq(10L), eq(1L), eq(2L))).thenReturn(vo);

        mockMvc.perform(post("/api/repos/10/adrs/1/supersede")
                        .accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supersedingAdrId\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("SUPERSEDED"))
                .andExpect(jsonPath("$.data.supersededBy").value(2));
    }
}
