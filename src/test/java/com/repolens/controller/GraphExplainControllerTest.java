package com.repolens.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.domain.dto.GraphExplainRequest;
import com.repolens.service.GraphExplainService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.repolens.controller.TestAuthUtils;

/**
 * GraphExplainController 单测（standaloneSetup，mock GraphExplainService）：
 * POST 返回 Result<String>，data 为服务给出的解说文案。
 */
class GraphExplainControllerTest {

    private GraphExplainService graphExplainService;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setup() {
        graphExplainService = mock(GraphExplainService.class);
        GraphExplainController controller = new GraphExplainController(graphExplainService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(TestAuthUtils.fixedUserIdResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    void explain_returnsExplanationString() throws Exception {
        when(graphExplainService.explain(eq(1L), eq(7L), any(GraphExplainRequest.class)))
                .thenReturn("请求从 Controller 进入，经 Service 层处理后落库。");

        GraphExplainRequest req = new GraphExplainRequest();
        req.setRootLabel("UserController.getUser [Controller]");
        req.setNodes(List.of("UserService.getUser [Service]"));
        req.setEdges(List.of("UserController.getUser -> UserService.getUser"));

        mockMvc.perform(post("/api/repos/7/graph/explain")
                        .header("X-User-Id", "1")
                        .contentType(APPLICATION_JSON)
                        .accept(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value("请求从 Controller 进入，经 Service 层处理后落库。"));
    }
}
