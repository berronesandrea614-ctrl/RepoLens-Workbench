package com.repolens.controller;

import com.repolens.common.exception.GlobalExceptionHandler;
import com.repolens.domain.enums.RepoIndexStatus;
import com.repolens.domain.enums.TaskStatus;
import com.repolens.domain.vo.RepoVO;
import com.repolens.domain.vo.SyncIndexResultVO;
import com.repolens.domain.vo.VectorizeResultVO;
import com.repolens.service.RepoAsyncIndexService;
import com.repolens.service.RepoService;
import com.repolens.service.ToolInvokeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.repolens.controller.TestAuthUtils;

class RepoControllerTest {

    private RepoService repoService;
    private ToolInvokeService toolInvokeService;
    private RepoAsyncIndexService repoAsyncIndexService;
    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        repoService = mock(RepoService.class);
        toolInvokeService = mock(ToolInvokeService.class);
        repoAsyncIndexService = mock(RepoAsyncIndexService.class);
        // toolInvokeService / repoAsyncIndexService 供对应端点测试用；其余协作者与被测端点无关，传 null 即可。
        RepoController controller = new RepoController(
                repoService, null, null, null, null, null, null, toolInvokeService, null, repoAsyncIndexService, null);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .setCustomArgumentResolvers(TestAuthUtils.fixedUserIdResolver())
                .build();
    }

    @Test
    void invokeTool_rejectsWriteToolAtDirectEndpoint() throws Exception {
        // 安全边界：写工具绝不能从只读调试端点触达，且根本不应到达 service 层。
        mockMvc.perform(post("/api/repos/7/tools/writeFileContent/invoke")
                        .contentType(APPLICATION_JSON).accept(APPLICATION_JSON)
                        .header("X-User-Id", "1")
                        .content("{\"filePath\":\"src/A.java\",\"content\":\"x\"}"))
                .andExpect(status().isForbidden());

        verify(toolInvokeService, never()).invoke(anyLong(), anyLong(), any(), anyString(), any());
    }

    @Test
    void invokeTool_rejectsEditToolAtDirectEndpoint() throws Exception {
        mockMvc.perform(post("/api/repos/7/tools/editFileContent/invoke")
                        .contentType(APPLICATION_JSON).accept(APPLICATION_JSON)
                        .header("X-User-Id", "1")
                        .content("{\"filePath\":\"src/A.java\",\"oldString\":\"x\",\"newString\":\"y\"}"))
                .andExpect(status().isForbidden());

        verify(toolInvokeService, never()).invoke(anyLong(), anyLong(), any(), anyString(), any());
    }

    @Test
    void invokeTool_rejectsCreateToolAtDirectEndpoint() throws Exception {
        mockMvc.perform(post("/api/repos/7/tools/createFileContent/invoke")
                        .contentType(APPLICATION_JSON).accept(APPLICATION_JSON)
                        .header("X-User-Id", "1")
                        .content("{\"filePath\":\"src/New.java\",\"content\":\"class New {}\"}"))
                .andExpect(status().isForbidden());

        verify(toolInvokeService, never()).invoke(anyLong(), anyLong(), any(), anyString(), any());
    }

    @Test
    void invokeTool_rejectsRunVerificationAtDirectEndpoint() throws Exception {
        mockMvc.perform(post("/api/repos/7/tools/runVerification/invoke")
                        .contentType(APPLICATION_JSON).accept(APPLICATION_JSON)
                        .header("X-User-Id", "1")
                        .content("{\"kind\":\"build\"}"))
                .andExpect(status().isForbidden());

        verify(toolInvokeService, never()).invoke(anyLong(), anyLong(), any(), anyString(), any());
    }

    @Test
    void listRepos_returnsRepoListJson() throws Exception {
        RepoVO repo = RepoVO.builder()
                .id(10L)
                .workspaceId(1L)
                .repoName("demo-repo")
                .repoUrl("https://github.com/acme/demo-repo.git")
                .branchName("main")
                .indexStatus(RepoIndexStatus.PENDING)
                .build();
        when(repoService.listRepos(eq(1L))).thenReturn(List.of(repo));

        mockMvc.perform(get("/api/repos").accept(APPLICATION_JSON).header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].id").value(10))
                .andExpect(jsonPath("$.data[0].repoName").value("demo-repo"))
                .andExpect(jsonPath("$.data[0].workspaceId").value(1));
    }

    @Test
    void deleteRepo_returnsSuccessAndCallsService() throws Exception {
        mockMvc.perform(delete("/api/repos/7").accept(APPLICATION_JSON).header("X-User-Id", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(repoService).deleteRepo(eq(1L), eq(7L));
    }

    @Test
    void listRepos_defaultsUserIdToOne() throws Exception {
        when(repoService.listRepos(eq(1L))).thenReturn(List.of());

        mockMvc.perform(get("/api/repos").accept(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void syncIndex_returnsPipelineSummary() throws Exception {
        when(repoAsyncIndexService.runSyncIndex(eq(7L), eq(1L))).thenReturn(SyncIndexResultVO.builder()
                .repoId(7L)
                .status(TaskStatus.SUCCESS)
                .traceId("sync-t")
                .vectorizeResult(VectorizeResultVO.builder()
                        .repoId(7L).pendingChunkCount(23).embeddedChunkCount(23).failedChunkCount(0)
                        .status(TaskStatus.SUCCESS).build())
                .build());

        mockMvc.perform(post("/api/repos/7/index/sync").accept(APPLICATION_JSON).header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.repoId").value(7))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.vectorizeResult.embeddedChunkCount").value(23));
    }

    @Test
    void syncIndex_surfacesFailedStage() throws Exception {
        when(repoAsyncIndexService.runSyncIndex(eq(7L), eq(1L))).thenReturn(SyncIndexResultVO.builder()
                .repoId(7L)
                .status(TaskStatus.FAILED)
                .failedStage("PARSE_CODE")
                .errorMsg("parse boom")
                .build());

        mockMvc.perform(post("/api/repos/7/index/sync").accept(APPLICATION_JSON).header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.failedStage").value("PARSE_CODE"))
                .andExpect(jsonPath("$.data.errorMsg").value("parse boom"));
    }
}
