package com.repolens.controller;

import com.repolens.domain.vo.AgentRunStepVO;
import com.repolens.domain.vo.AgentRunTraceVO;
import com.repolens.domain.vo.AgentRunVO;
import com.repolens.service.AgentRunService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.repolens.controller.TestAuthUtils;

/**
 * Agent 执行记录接口控制层单测（standaloneSetup + mock AgentRunService，不加载 Spring 上下文）。
 */
class AgentRunControllerTest {

    private AgentRunService agentRunService;
    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        agentRunService = mock(AgentRunService.class);
        AgentRunController controller = new AgentRunController(agentRunService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(TestAuthUtils.fixedUserIdResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    void list_returnsAgentRunJson() throws Exception {
        when(agentRunService.list(eq(1L), eq(7L), isNull())).thenReturn(List.of(
                AgentRunVO.builder().id(100L).question("how to pay?").mode("ask")
                        .iterations(3).toolCalls(2).status("DONE").stepCount(4).build()));

        mockMvc.perform(get("/api/repos/7/agent-runs").accept(APPLICATION_JSON).header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].id").value(100))
                .andExpect(jsonPath("$.data[0].question").value("how to pay?"))
                .andExpect(jsonPath("$.data[0].stepCount").value(4));
    }

    @Test
    void trace_returnsRunAndStepsJson() throws Exception {
        AgentRunTraceVO trace = AgentRunTraceVO.builder()
                .run(AgentRunVO.builder().id(100L).question("q").mode("code").status("DONE").stepCount(1).build())
                .steps(List.of(AgentRunStepVO.builder()
                        .id(1L).stepIndex(1).type("WRITE").toolName("writeFileContent")
                        .observationSummary("ok").targetFiles(List.of("src/A.java")).status("DONE").build()))
                .build();
        when(agentRunService.trace(eq(1L), eq(7L), eq(100L))).thenReturn(trace);

        mockMvc.perform(get("/api/repos/7/agent-runs/100/trace").accept(APPLICATION_JSON).header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.run.id").value(100))
                .andExpect(jsonPath("$.data.steps[0].type").value("WRITE"))
                .andExpect(jsonPath("$.data.steps[0].targetFiles[0]").value("src/A.java"));
    }
}
