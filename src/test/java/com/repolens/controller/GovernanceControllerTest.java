package com.repolens.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.common.exception.GlobalExceptionHandler;
import com.repolens.domain.entity.AgentMemoryEntity;
import com.repolens.domain.entity.SensitiveFileEntity;
import com.repolens.domain.vo.AgentsMdProposalVO;
import com.repolens.domain.vo.SensitiveFileVO;
import com.repolens.mapper.AgentMemoryMapper;
import com.repolens.mapper.SensitiveFileMapper;
import com.repolens.security.PermissionService;
import com.repolens.service.SensitiveFileService;
import com.repolens.service.impl.support.AgentRulesLoader;
import com.repolens.service.impl.support.AgentsMdProposer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GovernanceController 契约测试（standaloneSetup，无 Spring 上下文）。
 * 验证：GET /sensitive-files, POST /sensitive-files/recompute,
 *        GET /agents-md/proposal 的响应契约。
 */
class GovernanceControllerTest {

    private SensitiveFileService sensitiveFileService;
    private AgentRulesLoader agentRulesLoader;
    private AgentMemoryMapper agentMemoryMapper;
    private SensitiveFileMapper sensitiveFileMapper;
    private AgentsMdProposer agentsMdProposer;
    private PermissionService permissionService;
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @BeforeEach
    void setUp() {
        sensitiveFileService = mock(SensitiveFileService.class);
        agentRulesLoader = mock(AgentRulesLoader.class);
        agentMemoryMapper = mock(AgentMemoryMapper.class);
        sensitiveFileMapper = mock(SensitiveFileMapper.class);
        agentsMdProposer = mock(AgentsMdProposer.class);
        permissionService = mock(PermissionService.class);

        // Default: allow access
        when(permissionService.checkRepoPermission(anyLong(), anyLong())).thenReturn(true);

        GovernanceController controller = new GovernanceController(
                sensitiveFileService, agentRulesLoader,
                agentMemoryMapper, sensitiveFileMapper, agentsMdProposer, permissionService);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(TestAuthUtils.fixedUserIdResolver(1L))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    // ─── GET /sensitive-files ─────────────────────────────────────────────────

    @Test
    void listSensitiveFiles_returns200WithList() throws Exception {
        SensitiveFileVO vo = new SensitiveFileVO();
        vo.setFilePath("src/main/resources/application-prod.yml");
        vo.setSeverity("BLOCK");
        vo.setFinalScore(85);
        when(sensitiveFileService.list(anyLong(), anyLong())).thenReturn(List.of(vo));

        mockMvc.perform(get("/api/repos/42/governance/sensitive-files")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].filePath").value("src/main/resources/application-prod.yml"))
                .andExpect(jsonPath("$.data[0].severity").value("BLOCK"))
                .andExpect(jsonPath("$.data[0].finalScore").value(85));
    }

    @Test
    void listSensitiveFiles_emptyList_returns200() throws Exception {
        when(sensitiveFileService.list(anyLong(), anyLong())).thenReturn(List.of());

        mockMvc.perform(get("/api/repos/42/governance/sensitive-files")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray());
    }

    // ─── POST /sensitive-files/recompute ─────────────────────────────────────

    @Test
    void recompute_returns200WithRecomputedList() throws Exception {
        SensitiveFileVO vo = new SensitiveFileVO();
        vo.setFilePath("config/db.properties");
        vo.setSeverity("BLOCK");
        vo.setFinalScore(92);
        when(sensitiveFileService.recompute(anyLong(), anyLong())).thenReturn(List.of(vo));

        mockMvc.perform(post("/api/repos/42/governance/sensitive-files/recompute")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].filePath").value("config/db.properties"))
                .andExpect(jsonPath("$.data[0].severity").value("BLOCK"));
    }

    // ─── GET /agents-md/proposal ──────────────────────────────────────────────

    @Test
    void agentsMdProposal_returns200WithProposal() throws Exception {
        String current = "# Project Rules\n";
        String proposed = current + "\n## 敏感文件（自动汇总，请审阅）\n- `secrets.yml` — creds（勿擅改）\n";
        AgentsMdProposer.Proposal proposal =
                new AgentsMdProposer.Proposal(current, proposed, "+ secrets.yml", true);

        when(agentRulesLoader.loadRules(anyLong())).thenReturn(current);
        when(agentMemoryMapper.selectList(any())).thenReturn(List.of());
        when(sensitiveFileMapper.selectList(any())).thenReturn(List.of());
        when(agentsMdProposer.propose(anyLong(), anyString(), any(), any())).thenReturn(proposal);

        mockMvc.perform(get("/api/repos/42/governance/agents-md/proposal")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.hasChanges").value(true))
                .andExpect(jsonPath("$.data.currentContent").value(current))
                .andExpect(jsonPath("$.data.proposedContent").value(proposed))
                .andExpect(jsonPath("$.data.diffMarkdown").value("+ secrets.yml"));
    }

    @Test
    void agentsMdProposal_noAgentsMdFile_returnsNoChangesProposal() throws Exception {
        // loadRules returns null when file doesn't exist
        when(agentRulesLoader.loadRules(anyLong())).thenReturn(null);
        when(agentMemoryMapper.selectList(any())).thenReturn(List.of());
        when(sensitiveFileMapper.selectList(any())).thenReturn(List.of());

        AgentsMdProposer.Proposal emptyProposal =
                new AgentsMdProposer.Proposal("", "", "", false);
        when(agentsMdProposer.propose(anyLong(), any(), any(), any())).thenReturn(emptyProposal);

        mockMvc.perform(get("/api/repos/42/governance/agents-md/proposal")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.hasChanges").value(false));
    }

    // ─── IDOR guard: unauthorized user returns 403 ────────────────────────────

    @Test
    void agentsMdProposal_noPermission_returns403() throws Exception {
        when(permissionService.checkRepoPermission(anyLong(), anyLong())).thenReturn(false);

        mockMvc.perform(get("/api/repos/42/governance/agents-md/proposal")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }
}
