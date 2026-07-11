package com.repolens.controller;

import com.repolens.domain.vo.ChatMessageVO;
import com.repolens.domain.vo.ChatSessionVO;
import com.repolens.domain.vo.CodeReferenceVO;
import com.repolens.service.ChatSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.repolens.controller.TestAuthUtils;

/**
 * 会话历史控制层单测（standaloneSetup + mock ChatSessionService，不加载 Spring 上下文）。
 */
class ChatSessionControllerTest {

    private ChatSessionService chatSessionService;
    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        chatSessionService = mock(ChatSessionService.class);
        ChatSessionController controller = new ChatSessionController(chatSessionService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(TestAuthUtils.fixedUserIdResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    void listSessions_returnsSessionJson() throws Exception {
        when(chatSessionService.listSessions(eq(1L), eq(7L))).thenReturn(List.of(
                ChatSessionVO.builder().id(10L).title("登录流程").messageCount(3)
                        .lastMessagePreview("最新一条").build()));

        mockMvc.perform(get("/api/repos/7/sessions").accept(APPLICATION_JSON).header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].id").value(10))
                .andExpect(jsonPath("$.data[0].title").value("登录流程"))
                .andExpect(jsonPath("$.data[0].messageCount").value(3))
                .andExpect(jsonPath("$.data[0].lastMessagePreview").value("最新一条"));
    }

    @Test
    void listMessages_returnsMessageJsonWithReferences() throws Exception {
        when(chatSessionService.listMessages(eq(1L), eq(7L), eq(10L))).thenReturn(List.of(
                ChatMessageVO.builder().id(1L).role("USER").content("问题").references(List.of()).build(),
                ChatMessageVO.builder().id(2L).role("ASSISTANT").content("答案")
                        .references(List.of(CodeReferenceVO.builder().filePath("A.java").startLine(5).build()))
                        .build()));

        mockMvc.perform(get("/api/repos/7/sessions/10/messages").accept(APPLICATION_JSON).header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].role").value("USER"))
                .andExpect(jsonPath("$.data[1].role").value("ASSISTANT"))
                .andExpect(jsonPath("$.data[1].references[0].filePath").value("A.java"))
                .andExpect(jsonPath("$.data[1].references[0].startLine").value(5));
    }

    @Test
    void deleteSession_invokesServiceDelete() throws Exception {
        mockMvc.perform(delete("/api/repos/7/sessions/10").header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(chatSessionService, times(1)).deleteSession(eq(1L), eq(7L), eq(10L));
    }

    @Test
    void renameSession_invokesServiceRename() throws Exception {
        mockMvc.perform(put("/api/repos/7/sessions/10/title")
                        .contentType(APPLICATION_JSON)
                        .content("{\"title\":\"新标题\"}")
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(chatSessionService, times(1)).renameSession(eq(1L), eq(7L), eq(10L), eq("新标题"));
    }
}
