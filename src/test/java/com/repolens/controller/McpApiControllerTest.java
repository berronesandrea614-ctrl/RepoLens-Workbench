package com.repolens.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.common.exception.GlobalExceptionHandler;
import com.repolens.mcp.McpApiController;
import com.repolens.mcp.McpContextHolder;
import com.repolens.mcp.McpIdeStateHolder;
import com.repolens.mcp.McpTokenHolder;
import com.repolens.mcp.McpUiActionBroker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for /api/mcp/* helper endpoints.
 */
class McpApiControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private McpTokenHolder tokenHolder;
    private McpContextHolder contextHolder;
    private McpIdeStateHolder ideStateHolder;
    private McpUiActionBroker uiActionBroker;

    @BeforeEach
    void setup() {
        tokenHolder    = mock(McpTokenHolder.class);
        contextHolder  = mock(McpContextHolder.class);
        ideStateHolder = mock(McpIdeStateHolder.class);
        uiActionBroker = mock(McpUiActionBroker.class);

        when(tokenHolder.getToken()).thenReturn("abcd1234efgh5678");

        McpApiController controller = new McpApiController(tokenHolder, contextHolder, ideStateHolder, uiActionBroker);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(TestAuthUtils.fixedUserIdResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    void getToken_loopback_returnsToken() throws Exception {
        // MockMvc default remoteAddr is 127.0.0.1
        mockMvc.perform(get("/api/mcp/token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value("abcd1234efgh5678"));
    }

    @Test
    void getToken_nonLoopback_returns403() throws Exception {
        mockMvc.perform(get("/api/mcp/token")
                        .with(req -> { req.setRemoteAddr("192.168.1.100"); return req; }))
                .andExpect(status().isForbidden());
    }

    @Test
    void setContext_updatesRepoId() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("repoId", 5));

        mockMvc.perform(post("/api/mcp/context")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(contextHolder).setCurrentRepoId(5L);
    }

    @Test
    void setContext_updatesActiveFilePath() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("activeFilePath", "src/Main.java"));

        mockMvc.perform(post("/api/mcp/context")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(contextHolder).setActiveFilePath("src/Main.java");
    }

    @Test
    void uiEvents_nonLoopback_returns403() throws Exception {
        mockMvc.perform(get("/api/mcp/ui-events")
                        .with(req -> { req.setRemoteAddr("10.0.0.2"); return req; }))
                .andExpect(status().isForbidden());
    }

    // ── POST /api/mcp/ide-state ───────────────────────────────────────────────

    @Test
    void setIdeState_activeFile_callsHolder() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "activeFile", Map.of("filePath", "src/Main.java", "content", "public class Main {}")));

        mockMvc.perform(post("/api/mcp/ide-state")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(ideStateHolder).setActiveFile("src/Main.java", "public class Main {}");
    }

    @Test
    void setIdeState_selection_callsHolder() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "selection", Map.of(
                        "filePath", "src/Foo.java",
                        "startLine", 5,
                        "endLine", 10,
                        "text", "selected code block")));

        mockMvc.perform(post("/api/mcp/ide-state")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(ideStateHolder).setSelection("src/Foo.java", 5, 10, "selected code block");
    }

    @Test
    void setIdeState_bothFields_callsBothHolderMethods() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "activeFile", Map.of("filePath", "src/Main.java", "content", "// code"),
                "selection",  Map.of("filePath", "src/Main.java", "startLine", 1, "endLine", 1, "text", "// code")));

        mockMvc.perform(post("/api/mcp/ide-state")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(ideStateHolder).setActiveFile("src/Main.java", "// code");
        verify(ideStateHolder).setSelection("src/Main.java", 1, 1, "// code");
    }

    @Test
    void setIdeState_emptyBody_returns200WithoutCallingHolder() throws Exception {
        // Empty body — no fields provided — should return 200 without calling any setters.
        mockMvc.perform(post("/api/mcp/ide-state")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(ideStateHolder, org.mockito.Mockito.never()).setActiveFile(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(ideStateHolder, org.mockito.Mockito.never()).setSelection(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void setIdeState_nonLoopback_returns403() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "activeFile", Map.of("filePath", "src/Main.java", "content", "")));

        mockMvc.perform(post("/api/mcp/ide-state")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(req -> { req.setRemoteAddr("192.168.1.1"); return req; }))
                .andExpect(status().isForbidden());
    }
}
