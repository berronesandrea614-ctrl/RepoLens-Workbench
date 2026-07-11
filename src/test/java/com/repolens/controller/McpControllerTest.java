package com.repolens.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.common.exception.BizException;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.GlobalExceptionHandler;
import com.repolens.domain.vo.CodeGraphVO;
import com.repolens.domain.vo.FileContentVO;
import com.repolens.domain.vo.RagSearchResultVO;
import com.repolens.domain.vo.RequirementInsightVO;
import com.repolens.domain.vo.SymbolVO;
import com.repolens.mcp.McpContextHolder;
import com.repolens.mcp.McpController;
import com.repolens.mcp.McpIdeStateHolder;
import com.repolens.mcp.McpTokenHolder;
import com.repolens.mcp.McpUiActionBroker;
import com.repolens.service.CodeGraphService;
import com.repolens.service.RagRetrievalService;
import com.repolens.service.RequirementInsightService;
import com.repolens.service.SymbolQueryService;
import com.repolens.tool.ReadonlyToolService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract tests for the MCP JSON-RPC 2.0 endpoint.
 *
 * Tests:
 *  - tools/list returns all 8 registered tools
 *  - tools/call for each data tool returns result with content[{type,text}]
 *  - Token check: missing token → 401, wrong token → 401
 *  - Loopback check: non-loopback address → 403
 *  - Notification (no id) → 202
 *  - initialize → returns server info
 *  - UI action tools forwarded to McpUiActionBroker
 *  - read_file path escape → isError:true (via service layer)
 */
class McpControllerTest {

    private static final String VALID_TOKEN = "aaabbbcccddd00001111222233334444";
    private static final String TOKEN_HEADER = "X-RepoLens-Token";

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Mocks
    private McpTokenHolder tokenHolder;
    private McpContextHolder contextHolder;
    private McpIdeStateHolder ideStateHolder;
    private McpUiActionBroker uiActionBroker;
    private RagRetrievalService ragRetrievalService;
    private CodeGraphService codeGraphService;
    private SymbolQueryService symbolQueryService;
    private RequirementInsightService requirementInsightService;
    private ReadonlyToolService readonlyToolService;

    @BeforeEach
    void setup() {
        tokenHolder             = mock(McpTokenHolder.class);
        contextHolder           = mock(McpContextHolder.class);
        ideStateHolder          = mock(McpIdeStateHolder.class);
        uiActionBroker          = mock(McpUiActionBroker.class);
        ragRetrievalService     = mock(RagRetrievalService.class);
        codeGraphService        = mock(CodeGraphService.class);
        symbolQueryService      = mock(SymbolQueryService.class);
        requirementInsightService = mock(RequirementInsightService.class);
        readonlyToolService     = mock(ReadonlyToolService.class);

        // Default: valid token matches
        when(tokenHolder.matches(VALID_TOKEN)).thenReturn(true);
        when(tokenHolder.matches(null)).thenReturn(false);

        McpController controller = new McpController(
                tokenHolder, contextHolder, ideStateHolder, uiActionBroker,
                ragRetrievalService, codeGraphService, symbolQueryService,
                requirementInsightService, readonlyToolService, objectMapper);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    // ── Security gate tests ───────────────────────────────────────────────────

    @Test
    void missingToken_returns401() throws Exception {
        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toolsListRequest()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongToken_returns401() throws Exception {
        when(tokenHolder.matches("wrong-token")).thenReturn(false);

        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TOKEN_HEADER, "wrong-token")
                        .content(toolsListRequest()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nonLoopbackRemoteAddr_returns403() throws Exception {
        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TOKEN_HEADER, VALID_TOKEN)
                        .with(req -> { req.setRemoteAddr("10.0.0.1"); return req; })
                        .content(toolsListRequest()))
                .andExpect(status().isForbidden());
    }

    // ── MCP protocol tests ────────────────────────────────────────────────────

    @Test
    void notification_returns202NoBody() throws Exception {
        String notification = objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "method", "notifications/initialized",
                "params", Map.of()));

        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TOKEN_HEADER, VALID_TOKEN)
                        .content(notification))
                .andExpect(status().isAccepted());
    }

    @Test
    void initialize_returnsServerInfo() throws Exception {
        String req = objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "initialize",
                "params", Map.of("protocolVersion", "2024-11-05", "capabilities", Map.of())));

        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TOKEN_HEADER, VALID_TOKEN)
                        .content(req))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jsonrpc").value("2.0"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.result.protocolVersion").value("2024-11-05"))
                .andExpect(jsonPath("$.result.serverInfo.name").value("repolens"));
    }

    @Test
    void toolsList_returns8Tools() throws Exception {
        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TOKEN_HEADER, VALID_TOKEN)
                        .content(toolsListRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jsonrpc").value("2.0"))
                .andExpect(jsonPath("$.result.tools", hasSize(8)));
    }

    @Test
    void toolsList_toolNamesPresent() throws Exception {
        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TOKEN_HEADER, VALID_TOKEN)
                        .content(toolsListRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools[0].name").value("rag_search"))
                .andExpect(jsonPath("$.result.tools[0].inputSchema.type").value("object"))
                .andExpect(jsonPath("$.result.tools[0].inputSchema.required[0]").value("query"));
    }

    @Test
    void toolsCall_ragSearch_returnsTextContent() throws Exception {
        when(contextHolder.getCurrentRepoId()).thenReturn(1L);
        RagSearchResultVO vo = RagSearchResultVO.builder()
                .repoId(1L).query("test").topK(5).hitCount(0).results(List.of()).build();
        when(ragRetrievalService.retrieve(eq(1L), eq(1L), eq("test query"), anyInt())).thenReturn(vo);

        String req = toolsCallRequest("rag_search", Map.of("query", "test query"));

        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TOKEN_HEADER, VALID_TOKEN)
                        .content(req))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.content[0].type").value("text"))
                .andExpect(jsonPath("$.result.content[0].text").isString());
    }

    @Test
    void toolsCall_symbolSearch_returnsTextContent() throws Exception {
        when(contextHolder.getCurrentRepoId()).thenReturn(1L);
        SymbolVO sym = SymbolVO.builder().id(42L).className("MyService").build();
        when(symbolQueryService.searchSymbols(eq(1L), eq(1L), eq("MyService"))).thenReturn(List.of(sym));

        String req = toolsCallRequest("symbol_search", Map.of("symbolName", "MyService"));

        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TOKEN_HEADER, VALID_TOKEN)
                        .content(req))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.content[0].type").value("text"));
    }

    @Test
    void toolsCall_callGraph_returnsTextContent() throws Exception {
        when(contextHolder.getCurrentRepoId()).thenReturn(1L);
        SymbolVO sym = SymbolVO.builder().id(10L).className("Foo").methodName("bar").build();
        when(symbolQueryService.searchSymbols(eq(1L), eq(1L), eq("Foo"))).thenReturn(List.of(sym));
        CodeGraphVO graph = CodeGraphVO.builder().rootId("10").nodes(List.of()).edges(List.of()).build();
        when(codeGraphService.buildGraph(eq(1L), eq(1L), eq(10L), anyString(), anyInt(), anyDouble()))
                .thenReturn(graph);

        String req = toolsCallRequest("call_graph", Map.of("symbolName", "Foo"));

        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TOKEN_HEADER, VALID_TOKEN)
                        .content(req))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.content[0].type").value("text"));
    }

    @Test
    void toolsCall_requirementInsight_returnsTextContent() throws Exception {
        when(contextHolder.getCurrentRepoId()).thenReturn(1L);
        RequirementInsightVO vo = RequirementInsightVO.builder().steps(List.of()).build();
        when(requirementInsightService.insight(eq(1L), eq(1L), eq(7L))).thenReturn(vo);

        String req = toolsCallRequest("requirement_insight", Map.of("reqId", 7));

        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TOKEN_HEADER, VALID_TOKEN)
                        .content(req))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.content[0].type").value("text"));
    }

    @Test
    void toolsCall_readFile_returnsContent() throws Exception {
        when(contextHolder.getCurrentRepoId()).thenReturn(1L);
        FileContentVO fc = FileContentVO.builder()
                .repoId(1L).filePath("src/Main.java").content("public class Main {}").build();
        when(readonlyToolService.getFileContent(eq(1L), eq(1L), eq("src/Main.java"), any(), any()))
                .thenReturn(fc);

        String req = toolsCallRequest("read_file", Map.of("path", "src/Main.java"));

        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TOKEN_HEADER, VALID_TOKEN)
                        .content(req))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.content[0].text").value("public class Main {}"));
    }

    @Test
    void toolsCall_readFile_pathEscapeRejectedByService_returnsIsError() throws Exception {
        when(contextHolder.getCurrentRepoId()).thenReturn(1L);
        when(readonlyToolService.getFileContent(anyLong(), anyLong(), eq("../../../etc/passwd"), any(), any()))
                .thenThrow(new BizException(ErrorCode.BAD_REQUEST, "File path escapes repository root"));

        String req = toolsCallRequest("read_file", Map.of("path", "../../../etc/passwd"));

        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TOKEN_HEADER, VALID_TOKEN)
                        .content(req))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(jsonPath("$.result.content[0].text").value(
                        org.hamcrest.Matchers.containsString("escapes repository root")));
    }

    @Test
    void toolsCall_openFile_forwardsToBroker() throws Exception {
        when(uiActionBroker.push(eq("open_file"), any())).thenReturn(true);

        String req = toolsCallRequest("open_file", Map.of("path", "src/Main.java", "line", 42));

        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TOKEN_HEADER, VALID_TOKEN)
                        .content(req))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.content[0].text").value(
                        org.hamcrest.Matchers.containsString("open")));

        verify(uiActionBroker).push(eq("open_file"), any());
    }

    @Test
    void toolsCall_focusSymbol_forwardsToBroker() throws Exception {
        when(uiActionBroker.push(eq("focus_symbol"), any())).thenReturn(true);

        String req = toolsCallRequest("focus_symbol", Map.of("symbolId", 99));

        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TOKEN_HEADER, VALID_TOKEN)
                        .content(req))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false));

        verify(uiActionBroker).push(eq("focus_symbol"), any());
    }

    @Test
    void toolsCall_showRequirementViz_forwardsToBroker() throws Exception {
        when(uiActionBroker.push(eq("show_requirement_viz"), any())).thenReturn(true);

        String req = toolsCallRequest("show_requirement_viz", Map.of("reqId", 5));

        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TOKEN_HEADER, VALID_TOKEN)
                        .content(req))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false));

        verify(uiActionBroker).push(eq("show_requirement_viz"), any());
    }

    @Test
    void toolsCall_frontendNotConnected_degradesGracefully() throws Exception {
        when(uiActionBroker.push(anyString(), any())).thenReturn(false);

        String req = toolsCallRequest("open_file", Map.of("path", "README.md"));

        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TOKEN_HEADER, VALID_TOKEN)
                        .content(req))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.content[0].text").value(
                        org.hamcrest.Matchers.containsString("degraded")));
    }

    @Test
    void toolsCall_missingRepoIdNoContext_returnsIsError() throws Exception {
        when(contextHolder.getCurrentRepoId()).thenReturn(null);

        String req = toolsCallRequest("rag_search", Map.of("query", "something"));

        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TOKEN_HEADER, VALID_TOKEN)
                        .content(req))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(jsonPath("$.result.content[0].text").value(
                        org.hamcrest.Matchers.containsString("repoId is required")));
    }

    @Test
    void unknownMethod_returnsMethodNotFound() throws Exception {
        String req = objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", 9,
                "method", "nonexistent/method",
                "params", Map.of()));

        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TOKEN_HEADER, VALID_TOKEN)
                        .content(req))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32601));
    }

    @Test
    void resourcesList_returns2Resources() throws Exception {
        String req = objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", 10,
                "method", "resources/list",
                "params", Map.of()));

        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TOKEN_HEADER, VALID_TOKEN)
                        .content(req))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.resources", hasSize(2)))
                .andExpect(jsonPath("$.result.resources[0].uri").value("ide://active-file"));
    }

    @Test
    void resourcesRead_activeFile_withIdeState_returnsFileAndContent() throws Exception {
        // Rich state via McpIdeStateHolder
        when(ideStateHolder.getActiveFile()).thenReturn(
                new McpIdeStateHolder.ActiveFileState("src/Main.java", "public class Main {}"));

        String req = objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", 11,
                "method", "resources/read",
                "params", Map.of("uri", "ide://active-file")));

        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TOKEN_HEADER, VALID_TOKEN)
                        .content(req))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.contents[0].text").value(
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("src/Main.java"),
                                org.hamcrest.Matchers.containsString("public class Main {}"))))
                .andExpect(jsonPath("$.result.contents[0].uri").value("ide://active-file"))
                .andExpect(jsonPath("$.result.contents[0].mimeType").value("text/plain"));
    }

    @Test
    void resourcesRead_activeFile_noIdeState_degradesGracefully() throws Exception {
        // No ide-state set — falls back to contextHolder path
        when(ideStateHolder.getActiveFile()).thenReturn(null);
        when(contextHolder.getActiveFilePath()).thenReturn(null);

        String req = objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", 12,
                "method", "resources/read",
                "params", Map.of("uri", "ide://active-file")));

        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TOKEN_HEADER, VALID_TOKEN)
                        .content(req))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.contents[0].text").value(
                        org.hamcrest.Matchers.containsString("ide-state")));
    }

    @Test
    void resourcesRead_selection_withIdeState_returnsSelectionWithLines() throws Exception {
        when(ideStateHolder.getSelection()).thenReturn(
                new McpIdeStateHolder.SelectionState("src/Foo.java", 10, 15, "selected code"));

        String req = objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", 13,
                "method", "resources/read",
                "params", Map.of("uri", "ide://selection")));

        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TOKEN_HEADER, VALID_TOKEN)
                        .content(req))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.contents[0].text").value(
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("src/Foo.java"),
                                org.hamcrest.Matchers.containsString("lines 10-15"),
                                org.hamcrest.Matchers.containsString("selected code"))))
                .andExpect(jsonPath("$.result.contents[0].uri").value("ide://selection"));
    }

    @Test
    void resourcesRead_selection_noIdeState_degradesGracefully() throws Exception {
        when(ideStateHolder.getSelection()).thenReturn(null);
        when(contextHolder.getSelectionText()).thenReturn(null);

        String req = objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", 14,
                "method", "resources/read",
                "params", Map.of("uri", "ide://selection")));

        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TOKEN_HEADER, VALID_TOKEN)
                        .content(req))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.contents[0].text").value(
                        org.hamcrest.Matchers.containsString("ide-state")));
    }

    @Test
    void resourcesRead_unknownUri_returnsError() throws Exception {
        String req = objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", 15,
                "method", "resources/read",
                "params", Map.of("uri", "ide://unknown")));

        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TOKEN_HEADER, VALID_TOKEN)
                        .content(req))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32602));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String toolsListRequest() throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "tools/list",
                "params", Map.of()));
    }

    private String toolsCallRequest(String toolName, Map<String, Object> args) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", 2,
                "method", "tools/call",
                "params", Map.of("name", toolName, "arguments", args)));
    }
}
