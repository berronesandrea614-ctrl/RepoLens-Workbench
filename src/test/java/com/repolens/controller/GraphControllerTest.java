package com.repolens.controller;

import com.repolens.domain.vo.CodeGraphVO;
import com.repolens.domain.vo.GraphNodeVO;
import com.repolens.service.CodeGraphService;
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

class GraphControllerTest {

    private CodeGraphService codeGraphService;
    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        codeGraphService = mock(CodeGraphService.class);
        GraphController controller = new GraphController(codeGraphService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(TestAuthUtils.fixedUserIdResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    void returnsGraphJson() throws Exception {
        CodeGraphVO g = CodeGraphVO.builder()
                .rootId("1")
                .nodes(List.of(GraphNodeVO.builder().id("1").label("UserController.getUser")
                        .symbolType("API").resolved(true).build()))
                .edges(List.of())
                .nodeCount(1).edgeCount(0).truncated(false).build();
        when(codeGraphService.buildGraph(eq(1L), eq(7L), eq(5L), eq("callees"), eq(2), eq(0.0)))
                .thenReturn(g);

        mockMvc.perform(get("/api/repos/7/graph")
                        .param("rootSymbolId", "5").accept(APPLICATION_JSON).header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.rootId").value("1"))
                .andExpect(jsonPath("$.data.nodes[0].symbolType").value("API"))
                .andExpect(jsonPath("$.data.nodeCount").value(1));
    }
}
