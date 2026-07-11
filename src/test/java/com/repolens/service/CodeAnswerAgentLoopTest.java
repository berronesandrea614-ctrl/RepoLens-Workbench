package com.repolens.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.domain.dto.chat.CodeAnswerRequest;
import com.repolens.domain.entity.ChatSessionEntity;
import com.repolens.domain.entity.LlmCallLogEntity;
import com.repolens.domain.entity.RepoEntity;
import com.repolens.domain.vo.CodeAnswerVO;
import com.repolens.domain.vo.CodeReferenceVO;
import com.repolens.domain.vo.RagChunkVO;
import com.repolens.domain.vo.RagSearchResultVO;
import com.repolens.llm.impl.MockLlmClient;
import com.repolens.mapper.ChatMessageMapper;
import com.repolens.mapper.ChatSessionMapper;
import com.repolens.mapper.LlmCallLogMapper;
import com.repolens.mapper.RepoMapper;
import com.repolens.security.PermissionService;
import com.repolens.service.impl.CodeAnswerServiceImpl;
import com.repolens.service.impl.support.AgentLoopExecutor;
import com.repolens.service.impl.support.AgentRulesLoader;
import com.repolens.service.impl.support.AgentToolCatalog;
import com.repolens.service.impl.support.CodeAnswerPromptBuilder;
import com.repolens.service.impl.support.MemoryExtractor;
import com.repolens.service.impl.support.MentionContextInjector;
import com.repolens.service.support.EditFormatPolicy;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agentic 多步检索 loop 的端到端验证（离线，用 MockLlmClient 脚本化 tool_calls）。
 * 证明：开启 agent 后，LLM 会先发起一次工具调用（多步），工具经 ToolInvokeService 执行（带权限/审计），
 * 结果回填后模型再给出最终答案，loop 正常终止。
 */
@ExtendWith(MockitoExtension.class)
class CodeAnswerAgentLoopTest {

    @Mock
    private RepoMapper repoMapper;
    @Mock
    private PermissionService permissionService;
    @Mock
    private ChatSessionMapper chatSessionMapper;
    @Mock
    private ChatMessageMapper chatMessageMapper;
    @Mock
    private LlmCallLogMapper llmCallLogMapper;
    @Mock
    private RagRetrievalService ragRetrievalService;
    @Mock
    private ToolInvokeService toolInvokeService;
    @Mock
    private AgentMemoryService agentMemoryService;
    @Mock
    private MemoryExtractor memoryExtractor;
    @Mock
    private com.repolens.service.impl.support.RequirementExtractor requirementExtractor;
    @Mock
    private com.repolens.service.RequirementService requirementService;
    @Mock
    private com.repolens.mapper.FileChangeLogMapper fileChangeLogMapper;
    @Mock
    private com.repolens.service.AgentRunService agentRunService;
    @Mock
    private com.repolens.mapper.AgentRunPlanMapper agentRunPlanMapper;
    @Mock
    private com.repolens.mapper.AgentRunMapper agentRunMapper;
    @Mock
    private MentionContextInjector mentionContextInjector;
    @Mock
    private AgentRulesLoader agentRulesLoader;

    private CodeAnswerServiceImpl service;

    @BeforeEach
    void setUp() {
        CodeAnswerPromptBuilder promptBuilder = new CodeAnswerPromptBuilder();
        ReflectionTestUtils.setField(promptBuilder, "maxContextChars", 12000);

        com.repolens.llm.config.LlmRuntimeConfig runtimeConfig = new com.repolens.llm.config.LlmRuntimeConfig(null);
        ReflectionTestUtils.setField(runtimeConfig, "modelName", "mock-code-assistant");
        ReflectionTestUtils.setField(runtimeConfig, "timeoutMs", 15000);

        // 真实 MockLlmClient：带 tools 时先返回 searchCodeChunks 调用，拿到工具结果后给最终答案。
        MockLlmClient mockLlmClient = new MockLlmClient(runtimeConfig);
        ReflectionTestUtils.setField(mockLlmClient, "mockForceFail", false);

        com.repolens.hooks.HookDispatcher hookDispatcher = mock(com.repolens.hooks.HookDispatcher.class);
        when(hookDispatcher.dispatchPreToolUse(any())).thenReturn(com.repolens.hooks.HookResult.allow());
        AgentLoopExecutor executor = new AgentLoopExecutor(mockLlmClient, toolInvokeService, new ObjectMapper(),
                llmCallLogMapper, null, mock(com.repolens.service.support.AgentControlToolHandler.class),
                new com.repolens.service.support.TodoState(),
                mock(com.repolens.service.support.AgentTaskDispatcher.class),
                mock(com.repolens.mapper.AgentRunPlanMapper.class),
                mock(com.repolens.service.support.context.ContextManager.class),
                hookDispatcher,
                new com.repolens.service.support.SteeringQueue());
        ReflectionTestUtils.setField(executor, "maxToolTurns", 8);
        ReflectionTestUtils.setField(executor, "wallClockBudgetMs", 90000);
        EditFormatPolicy editFormatPolicy = new EditFormatPolicy(runtimeConfig);
        ReflectionTestUtils.setField(editFormatPolicy, "editFormat", "auto");

        service = new CodeAnswerServiceImpl(
                repoMapper, permissionService, chatSessionMapper, chatMessageMapper, llmCallLogMapper,
                ragRetrievalService, promptBuilder, mockLlmClient, new ObjectMapper(),
                executor, new com.repolens.service.impl.support.AgentPlanner(mockLlmClient, runtimeConfig, new ObjectMapper()),
                new AgentToolCatalog(true, false), new com.repolens.common.util.PromptInjectionGuard(),
                new com.repolens.service.impl.support.ConversationHistoryLoader(chatMessageMapper),
                agentMemoryService, memoryExtractor, new com.repolens.service.impl.support.MemoryMetrics(),
                requirementExtractor, requirementService, runtimeConfig, fileChangeLogMapper, agentRunService,
                agentRunPlanMapper, agentRunMapper,
                mentionContextInjector, agentRulesLoader,
                editFormatPolicy);
        ReflectionTestUtils.setField(service, "maxAnswerChars", 4000);
        // mention/agentRules 默认返回空，不影响 agent loop 断言。
        when(mentionContextInjector.buildMentionEvidence(any(), any(), any())).thenReturn("");
        when(agentRulesLoader.loadRules(any())).thenReturn(null);
        ReflectionTestUtils.setField(service, "agentEnabled", true);
        ReflectionTestUtils.setField(service, "agentMaxIterations", 5);
        ReflectionTestUtils.setField(service, "longtermMemoryEnabled", true);
        ReflectionTestUtils.setField(service, "recallK", 5);

        RepoEntity repo = new RepoEntity();
        repo.setId(5L);
        repo.setWorkspaceId(1L);
        repo.setRepoName("demo");
        when(repoMapper.selectById(5L)).thenReturn(repo);
        when(permissionService.checkRepoPermission(1L, 5L)).thenReturn(true);
        when(chatSessionMapper.insert(any(ChatSessionEntity.class))).thenAnswer(inv -> {
            Object entity = inv.getArgument(0);
            ReflectionTestUtils.setField(entity, "id", 202L);
            return 1;
        });
    }

    @Test
    void agentAnswer_shouldCallToolThenProduceFinalAnswer() {
        when(ragRetrievalService.retrieve(eq(5L), eq(1L), any(), any())).thenReturn(buildRagResult());
        // 工具执行返回一条额外证据，证明 ToolInvokeService 真的被 agent 调用了。
        when(toolInvokeService.invoke(eq(1L), eq(5L), eq(202L), eq("searchCodeChunks"), anyMap(), any(), any()))
                .thenReturn(List.of(CodeReferenceVO.builder()
                        .filePath("src/main/java/com/example/UserService.java")
                        .startLine(40).endLine(60).build()));

        CodeAnswerRequest request = new CodeAnswerRequest();
        request.setQuestion("创建用户在哪里实现？");
        request.setTopK(5);

        CodeAnswerVO response = service.answer(5L, 1L, request);

        // 多步证据：工具被调用了恰好一次（loop 第一轮发起调用，第二轮终止）。
        verify(toolInvokeService, times(1)).invoke(eq(1L), eq(5L), eq(202L), eq("searchCodeChunks"), anyMap(), any(), any());
        // 最终答案来自 mock agent 收尾。
        Assertions.assertTrue(response.getAnswer().contains("mock agent"));

        // 缺陷#1 修复验证：agent 工具检索到的新证据（UserService.java:40）已并入引用，
        // 与初始 RAG 证据（UserController.java:12）一起，共 2 条，可追溯。
        Assertions.assertEquals(2, response.getReferences().size());
        Assertions.assertTrue(response.getReferences().stream()
                .anyMatch(r -> r.getFilePath().contains("UserService.java")));
        Assertions.assertTrue(response.getReferences().stream()
                .anyMatch(r -> r.getFilePath().contains("UserController.java")));

        // 轨迹可视化数据：agentMode + steps 非空，记录了工具调用。
        Assertions.assertTrue(response.getAgentMode());
        Assertions.assertNotNull(response.getAgentSteps());
        Assertions.assertEquals(1, response.getAgentSteps().size());
        Assertions.assertEquals("searchCodeChunks", response.getAgentSteps().get(0).getToolName());
        Assertions.assertEquals(1, response.getAgentToolCalls());

        // agent 成功路径：每次真实 LLM 调用落一条 llm_call_log（MockLlmClient 共 2 次调用：
        // 第 1 轮返回 tool_calls，第 2 轮返回最终答案），共 2 条成功日志，无合成汇总条目。
        ArgumentCaptor<LlmCallLogEntity> logCaptor = ArgumentCaptor.forClass(LlmCallLogEntity.class);
        verify(llmCallLogMapper, times(2)).insert(logCaptor.capture());
        logCaptor.getAllValues().forEach(entry -> Assertions.assertTrue(entry.getSuccess()));
    }

    private RagSearchResultVO buildRagResult() {
        return RagSearchResultVO.builder()
                .repoId(5L)
                .query("创建用户在哪里实现？")
                .topK(5)
                .hitCount(1)
                .degraded(false)
                .results(List.of(RagChunkVO.builder()
                        .chunkId("chunk-1")
                        .filePath("src/main/java/com/example/UserController.java")
                        .chunkType("METHOD")
                        .language("java")
                        .startLine(12)
                        .endLine(30)
                        .score(0.98f)
                        .content("public User createUser(UserCreateRequest request) { ... }")
                        .contentPreview("UserController createUser 用户")
                        .build()))
                .build();
    }
}
