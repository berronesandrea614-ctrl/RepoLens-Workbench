package com.repolens.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.domain.dto.chat.CodeAnswerRequest;
import com.repolens.domain.entity.ChatMessageEntity;
import com.repolens.domain.entity.ChatSessionEntity;
import com.repolens.domain.entity.LlmCallLogEntity;
import com.repolens.domain.entity.RepoEntity;
import com.repolens.domain.vo.CodeAnswerVO;
import com.repolens.domain.vo.RagChunkVO;
import com.repolens.domain.vo.RagSearchResultVO;
import com.repolens.llm.LlmClient;
import com.repolens.llm.LlmClientException;
import com.repolens.llm.config.LlmRuntimeConfig;
import com.repolens.llm.model.LlmResponse;
import com.repolens.mapper.ChatMessageMapper;
import com.repolens.mapper.ChatSessionMapper;
import com.repolens.mapper.LlmCallLogMapper;
import com.repolens.mapper.RepoMapper;
import com.repolens.security.PermissionService;
import com.repolens.service.impl.CodeAnswerServiceImpl;
import com.repolens.common.util.PromptInjectionGuard;
import com.repolens.service.impl.support.AgentLoopExecutor;
import com.repolens.service.impl.support.AgentRulesLoader;
import com.repolens.service.impl.support.AgentToolCatalog;
import com.repolens.service.impl.support.CodeAnswerPromptBuilder;
import com.repolens.service.impl.support.MemoryExtractor;
import com.repolens.service.impl.support.MentionContextInjector;
import com.repolens.service.impl.support.RequirementExtractor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// setUp() installs global happy-path stubs used by most tests; the two new executor/handler
// tests never reach the service's retrieval logic, so those stubs go unused.
// LENIENT mode avoids UnnecessaryStubbingException while keeping the shared setup concise.
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class CodeAnswerServiceImplTest {

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
    private LlmClient llmClient;
    @Mock
    private AgentLoopExecutor agentLoopExecutor;
    @Mock
    private AgentMemoryService agentMemoryService;
    @Mock
    private MemoryExtractor memoryExtractor;
    @Mock
    private RequirementExtractor requirementExtractor;
    @Mock
    private RequirementService requirementService;
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
    @Mock
    private com.repolens.service.support.EditFormatPolicy editFormatPolicy;

    private CodeAnswerServiceImpl service;

    @BeforeEach
    void setUp() {
        CodeAnswerPromptBuilder promptBuilder = new CodeAnswerPromptBuilder();
        ReflectionTestUtils.setField(promptBuilder, "maxContextChars", 12000);
        LlmRuntimeConfig runtimeConfig = new LlmRuntimeConfig(null);
        ReflectionTestUtils.setField(runtimeConfig, "modelName", "deepseek-chat");
        ReflectionTestUtils.setField(runtimeConfig, "timeoutMs", 15000);
        service = new CodeAnswerServiceImpl(
                repoMapper,
                permissionService,
                chatSessionMapper,
                chatMessageMapper,
                llmCallLogMapper,
                ragRetrievalService,
                promptBuilder,
                llmClient,
                new ObjectMapper(),
                agentLoopExecutor,
                new com.repolens.service.impl.support.AgentPlanner(llmClient, runtimeConfig, new ObjectMapper()),
                new AgentToolCatalog(true, false),
                new PromptInjectionGuard(),
                new com.repolens.service.impl.support.ConversationHistoryLoader(chatMessageMapper),
                agentMemoryService,
                memoryExtractor,
                new com.repolens.service.impl.support.MemoryMetrics(),
                requirementExtractor,
                requirementService,
                runtimeConfig,
                fileChangeLogMapper,
                agentRunService,
                agentRunPlanMapper,
                agentRunMapper,
                mentionContextInjector,
                agentRulesLoader,
                editFormatPolicy
        );
        ReflectionTestUtils.setField(service, "maxAnswerChars", 4000);
        // 默认关闭 agent，走原单轮链路；agent 行为在 CodeAnswerAgentLoopTest 单独验证。
        ReflectionTestUtils.setField(service, "agentEnabled", false);
        // 默认 mention/agentRules 返回空，不影响已有测试断言。
        when(mentionContextInjector.buildMentionEvidence(any(), any(), any())).thenReturn("");
        when(agentRulesLoader.loadRules(any())).thenReturn(null);
        ReflectionTestUtils.setField(service, "agentMaxIterations", 5);
        // 长期记忆开启：mock 的 recall/extract 默认返回空，不改变原有断言，仅覆盖记忆路径。
        ReflectionTestUtils.setField(service, "longtermMemoryEnabled", true);
        ReflectionTestUtils.setField(service, "recallK", 5);

        RepoEntity repo = new RepoEntity();
        repo.setId(5L);
        repo.setWorkspaceId(1L);
        repo.setRepoName("demo");
        repo.setRepoUrl("file:///demo");
        repo.setBranchName("main");
        when(repoMapper.selectById(5L)).thenReturn(repo);
        when(permissionService.checkRepoPermission(1L, 5L)).thenReturn(true);
        when(chatSessionMapper.insert(org.mockito.ArgumentMatchers.<ChatSessionEntity>any())).thenAnswer(invocation -> {
            Object entity = invocation.getArgument(0);
            ReflectionTestUtils.setField(entity, "id", 101L);
            return 1;
        });
    }

    @Test
    void answer_shouldReturnReferencesAndLogSuccessWhenLlmSucceeds() {
        when(ragRetrievalService.retrieve(eq(5L), eq(1L), eq("创建用户接口在哪里？"), eq(5)))
                .thenReturn(buildRagResult());
        when(llmClient.generate(any())).thenReturn(LlmResponse.builder()
                .content("创建入口位于 [src/main/java/com/example/UserController.java:12-30]。")
                .modelName("deepseek-chat")
                .promptTokens(120)
                .completionTokens(24)
                .costMs(18L)
                .success(true)
                .build());

        CodeAnswerRequest request = new CodeAnswerRequest();
        request.setQuestion("创建用户接口在哪里？");
        request.setTopK(5);

        CodeAnswerVO response = service.answer(5L, 1L, request);

        Assertions.assertEquals(1, response.getReferences().size());
        Assertions.assertFalse(response.getDegraded());
        Assertions.assertTrue(response.getAnswer().contains("UserController"));
        Assertions.assertEquals("deepseek-chat", response.getModelName());

        ArgumentCaptor<LlmCallLogEntity> logCaptor = ArgumentCaptor.forClass(LlmCallLogEntity.class);
        verify(llmCallLogMapper).insert(logCaptor.capture());
        Assertions.assertTrue(logCaptor.getValue().getSuccess());
        Assertions.assertNull(logCaptor.getValue().getErrorCode());
    }

    @Test
    void answer_shouldDegradeAndLogFailureWhenLlmThrowsClientException() {
        when(ragRetrievalService.retrieve(eq(5L), eq(1L), eq("创建用户接口在哪里？"), eq(5)))
                .thenReturn(buildRagResult());
        when(llmClient.generate(any()))
                .thenThrow(new LlmClientException("LLM_CONFIG_MISSING", "LLM api-key is not configured"));

        CodeAnswerRequest request = new CodeAnswerRequest();
        request.setQuestion("创建用户接口在哪里？");
        request.setTopK(5);

        CodeAnswerVO response = service.answer(5L, 1L, request);

        Assertions.assertTrue(response.getDegraded());
        Assertions.assertTrue(response.getDegradeReason().contains("LLM failed"));
        Assertions.assertEquals(1, response.getReferences().size());
        Assertions.assertTrue(response.getAnswer().contains("可用证据"));

        ArgumentCaptor<LlmCallLogEntity> logCaptor = ArgumentCaptor.forClass(LlmCallLogEntity.class);
        verify(llmCallLogMapper).insert(logCaptor.capture());
        Assertions.assertFalse(logCaptor.getValue().getSuccess());
        Assertions.assertEquals("LLM_CONFIG_MISSING", logCaptor.getValue().getErrorCode());

        ArgumentCaptor<ChatMessageEntity> messageCaptor = ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(chatMessageMapper, org.mockito.Mockito.times(2)).insert(messageCaptor.capture());
        Assertions.assertEquals("ASSISTANT", messageCaptor.getAllValues().get(1).getRole());
    }

    @Test
    void singleShotIncludesConversationHistoryInLlmRequest() {
        // 历史：一问一答（USER "先前的问题" → ASSISTANT "先前的回答"），按 id 升序（loader 的取序）。
        ChatMessageEntity priorUser = new ChatMessageEntity();
        priorUser.setId(1L);
        priorUser.setSessionId(101L);
        priorUser.setRole("USER");
        priorUser.setContent("先前的问题");
        ChatMessageEntity priorAssistant = new ChatMessageEntity();
        priorAssistant.setId(2L);
        priorAssistant.setSessionId(101L);
        priorAssistant.setRole("ASSISTANT");
        priorAssistant.setContent("先前的回答");
        // ConversationHistoryLoader 用 selectList 拉该会话全部历史再回喂（先加载历史，再落当前 USER 消息）。
        when(chatMessageMapper.selectList(any())).thenReturn(List.of(priorUser, priorAssistant));
        // 单测无 Spring @Value 注入，需显式打开短期记忆预算，否则 loader 因 maxTurns<=0 返回空历史。
        ReflectionTestUtils.setField(service, "historyTurns", 3);
        ReflectionTestUtils.setField(service, "historyMaxChars", 4000);

        when(ragRetrievalService.retrieve(eq(5L), eq(1L), eq("创建用户接口在哪里？"), eq(5)))
                .thenReturn(buildRagResult());
        when(llmClient.generate(any())).thenReturn(LlmResponse.builder()
                .content("创建入口位于 [src/main/java/com/example/UserController.java:12-30]。")
                .modelName("deepseek-chat")
                .promptTokens(120)
                .completionTokens(24)
                .costMs(18L)
                .success(true)
                .build());

        CodeAnswerRequest request = new CodeAnswerRequest();
        request.setQuestion("创建用户接口在哪里？");
        request.setTopK(5);

        service.answer(5L, 1L, request);

        ArgumentCaptor<com.repolens.llm.model.LlmRequest> requestCaptor =
                ArgumentCaptor.forClass(com.repolens.llm.model.LlmRequest.class);
        verify(llmClient).generate(requestCaptor.capture());
        com.repolens.llm.model.LlmRequest captured = requestCaptor.getValue();

        List<com.repolens.llm.model.LlmMessage> messages = captured.getMessages();
        Assertions.assertNotNull(messages, "single-shot 请求必须携带 messages");
        // 期望形状：[system] + 历史[user, assistant] + [当前 user]，共 4 条。
        Assertions.assertEquals(4, messages.size(), "messages = system + 2 条历史 + 当前 user");

        Assertions.assertEquals("system", messages.get(0).getRole());
        Assertions.assertEquals(captured.getSystemPrompt(), messages.get(0).getContent());

        // 历史部分（旧→新），必须原样回喂上一轮问答，且不含本轮当前问题。
        Assertions.assertEquals("user", messages.get(1).getRole());
        Assertions.assertEquals("先前的问题", messages.get(1).getContent());
        Assertions.assertEquals("assistant", messages.get(2).getRole());
        Assertions.assertEquals("先前的回答", messages.get(2).getContent());

        // 末条是本轮 user，内容即 prompt builder 构建出的 userPrompt。
        com.repolens.llm.model.LlmMessage last = messages.get(3);
        Assertions.assertEquals("user", last.getRole());
        Assertions.assertEquals(captured.getUserPrompt(), last.getContent());
        Assertions.assertTrue(last.getContent().contains("创建用户接口在哪里？"),
                "末条 user 应携带当前问题");

        // 当前问题不得被塞进历史部分（防“空心记忆”回归：历史真实是上一轮，而非重复本轮）。
        Assertions.assertFalse(messages.get(1).getContent().contains("创建用户接口在哪里？"));
        Assertions.assertFalse(messages.get(2).getContent().contains("创建用户接口在哪里？"));
    }

    @Test
    void answer_shouldRefuseWhenNoEvidence() {
        when(ragRetrievalService.retrieve(eq(5L), eq(1L), eq("创建用户接口在哪里？"), eq(5)))
                .thenReturn(RagSearchResultVO.builder()
                        .repoId(5L)
                        .query("创建用户接口在哪里？")
                        .topK(5)
                        .hitCount(0)
                        .degraded(false)
                        .degradeReason(null)
                        .results(List.of())
                        .build());

        CodeAnswerRequest request = new CodeAnswerRequest();
        request.setQuestion("创建用户接口在哪里？");
        request.setTopK(5);

        CodeAnswerVO response = service.answer(5L, 1L, request);

        Assertions.assertTrue(response.getDegraded());
        Assertions.assertEquals("insufficient evidence", response.getDegradeReason());
        Assertions.assertTrue(response.getAnswer().contains("无法可靠回答"));
        verify(llmClient, never()).generate(any());
        verify(llmCallLogMapper, never()).insert(org.mockito.ArgumentMatchers.<LlmCallLogEntity>any());
    }

    /**
     * Fix #2 regression guard: a valid @file mention alone must bypass the weak-evidence gate.
     *
     * Scenario: RAG returns zero hits (weakEvidence=true, no history, agent disabled),
     * BUT the request carries one file mention whose content is resolved by
     * mentionContextInjector. Before the fix the service would early-return with
     * NO_EVIDENCE_ANSWER; after the fix it must proceed to LLM and include the mention block.
     */
    @Test
    void answer_weakEvidenceWithMention_doesNotEarlyReturn_promptContainsMentionBlock() {
        // RAG returns no results → weakEvidence=true
        when(ragRetrievalService.retrieve(eq(5L), eq(1L), eq("这个文件里有什么？"), eq(5)))
                .thenReturn(RagSearchResultVO.builder()
                        .repoId(5L)
                        .query("这个文件里有什么？")
                        .topK(5)
                        .hitCount(0)
                        .degraded(false)
                        .degradeReason(null)
                        .results(List.of())
                        .build());

        // The file mention resolves to non-empty evidence
        String mentionEvidence = "[Mention-1]\nsource: @提及\ntype: file\nvalue: Foo.java\ncontent:\npublic class Foo {}\n\n";
        when(mentionContextInjector.buildMentionEvidence(eq(1L), eq(5L), any()))
                .thenReturn(mentionEvidence);

        // LLM succeeds
        when(llmClient.generate(any())).thenReturn(LlmResponse.builder()
                .content("Foo.java 包含一个空的 Foo 类。")
                .modelName("deepseek-chat")
                .promptTokens(50)
                .completionTokens(10)
                .costMs(5L)
                .success(true)
                .build());

        com.repolens.domain.dto.chat.MentionDTO mention = new com.repolens.domain.dto.chat.MentionDTO();
        mention.setType("file");
        mention.setValue("Foo.java");

        CodeAnswerRequest request = new CodeAnswerRequest();
        request.setQuestion("这个文件里有什么？");
        request.setTopK(5);
        request.setMentions(List.of(mention));

        CodeAnswerVO response = service.answer(5L, 1L, request);

        // Must NOT early-return with no-evidence answer
        Assertions.assertFalse(response.getDegraded(), "mention should bypass weak-evidence gate");
        Assertions.assertFalse(response.getAnswer().contains("无法可靠回答"),
                "no-evidence answer must not appear when mention is present");

        // LLM must have been called (not short-circuited)
        ArgumentCaptor<com.repolens.llm.model.LlmRequest> captor =
                ArgumentCaptor.forClass(com.repolens.llm.model.LlmRequest.class);
        verify(llmClient).generate(captor.capture());

        // User prompt must contain the mention evidence block
        String userPrompt = captor.getValue().getUserPrompt();
        Assertions.assertTrue(userPrompt.contains("Mention-1"),
                "user prompt must include mention evidence block");
        Assertions.assertTrue(userPrompt.contains("Foo.java"),
                "user prompt must include the mentioned file value");
    }

    /**
     * Regression guard for the "double-agent-run on bookkeeping failure" bug.
     *
     * Scenario: runStreaming succeeds and answer tokens are flushed; then the post-answer
     * saveChatMessage (ASSISTANT row) throws a RuntimeException (e.g. transient DB error).
     * Before the fix, the catch block in streamAgent would fall through to
     * streamAgentNonStreaming → agentLoopExecutor.run(), causing duplicate llm_call_log /
     * tool_call_log rows and potentially duplicate file writes.
     *
     * After the fix: the answerStreamed flag is true when the exception fires, so
     * streamAgent rethrows without invoking the non-streaming fallback.
     */
    @Test
    void answerStream_bookkeepingFailureAfterStreaming_doesNotReRunAgent() throws Exception {
        // Enable agent mode so the weak-evidence branch routes to streamAgent
        ReflectionTestUtils.setField(service, "agentEnabled", true);

        // Return non-empty references whose content does NOT match the question's keywords
        // → hasSufficientEvidence=false → isWeakEvidence=true → streamAgent path chosen
        // (keywords from "xyzzy flux stream" = {xyzzy, flux, stream}; absent from UserController content)
        String question = "xyzzy flux stream";
        when(ragRetrievalService.retrieve(eq(5L), eq(1L), eq(question), any()))
                .thenReturn(buildRagResult());

        // runStreaming succeeds (answer non-empty, no discovered refs — ctx.references non-empty
        // so noEvidenceAtAll=false and the code reaches saveChatMessage for ASSISTANT)
        AgentLoopExecutor.AgentResult agentSuccess = AgentLoopExecutor.AgentResult.builder()
                .answer("streaming answer")
                .iterations(1).toolCallCount(0).hitMaxIterations(false)
                .steps(List.of()).discoveredReferences(List.of())
                .build();
        when(agentLoopExecutor.runStreaming(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(agentSuccess);

        // chatMessageMapper.insert: first call (USER message in prepareAnswer) succeeds;
        // second call (ASSISTANT message in doStreamAgentTrue post-answer bookkeeping) throws.
        when(chatMessageMapper.insert(any(ChatMessageEntity.class)))
                .thenReturn(1)
                .thenThrow(new RuntimeException("DB failure on ASSISTANT insert"));

        // SseEmitter.onCompletion is only invoked when a real HTTP handler is wired; override
        // complete/completeWithError instead so the latch fires in the unit-test context too.
        CountDownLatch done = new CountDownLatch(1);
        SseEmitter emitter = new SseEmitter(5000L) {
            @Override
            public void complete() {
                super.complete();
                done.countDown();
            }
            @Override
            public void completeWithError(Throwable failure) {
                super.completeWithError(failure);
                done.countDown();
            }
        };

        CodeAnswerRequest request = new CodeAnswerRequest();
        request.setQuestion(question);
        request.setTopK(5);

        service.answerStream(5L, 1L, request, emitter);
        Assertions.assertTrue(done.await(3, TimeUnit.SECONDS), "SSE emitter should complete within 3 s");

        // runStreaming called exactly once — bookkeeping failure must NOT trigger a second agent run
        verify(agentLoopExecutor, times(1))
                .runStreaming(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any());
        // Non-streaming fallback (agentLoopExecutor.run) must never be called
        verify(agentLoopExecutor, never())
                .run(any(), any(), any(), any(), any(), any(), anyInt(), anyInt());
    }

    // -----------------------------------------------------------------------
    // Finding #1a: sseExecutor rejection → "系统繁忙" error event + completeWithError
    // -----------------------------------------------------------------------

    /**
     * When {@code sseExecutor} is saturated and throws {@link RejectedExecutionException},
     * {@code answerStream} must:
     * (a) attempt to emit a "系统繁忙" error event (send called before completeWithError);
     * (b) call {@code emitter.completeWithError(RejectedExecutionException)} — never hang or silently swallow.
     *
     * Injection: replace sseExecutor field with a shut-down executor whose submit() always rejects.
     */
    @Test
    void answerStream_sseRejected_emitsErrorEventAndCompletesWithError() throws Exception {
        // A shut-down ThreadPoolExecutor throws RejectedExecutionException on any submit().
        ThreadPoolExecutor rejectingExec = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1));
        rejectingExec.shutdown();
        ReflectionTestUtils.setField(service, "sseExecutor", rejectingExec);

        AtomicBoolean sendCalled = new AtomicBoolean(false);
        AtomicBoolean errorCompleted = new AtomicBoolean(false);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        SseEmitter emitter = new SseEmitter(1000L) {
            @Override
            public void send(SseEventBuilder builder) {
                // Record that a send was attempted (the "系统繁忙" error event).
                sendCalled.set(true);
                // Do NOT call super — no real HTTP handler in unit-test context.
            }
            @Override
            public void completeWithError(Throwable failure) {
                errorCompleted.set(true);
                errorRef.set(failure);
                // Do NOT call super — avoids NPE from null handler in unit-test context.
            }
        };

        CodeAnswerRequest request = new CodeAnswerRequest();
        request.setQuestion("压测请求");

        // answerStream must return immediately after catching RejectedExecutionException
        // (no blocking wait for background thread because submission was rejected).
        service.answerStream(5L, 1L, request, emitter);

        Assertions.assertTrue(sendCalled.get(),
                "An error event should have been sent before completing the emitter");
        Assertions.assertTrue(errorCompleted.get(),
                "emitter.completeWithError should be called when sseExecutor rejects");
        Assertions.assertInstanceOf(RejectedExecutionException.class, errorRef.get(),
                "completeWithError must be called with the RejectedExecutionException");
    }

    // -----------------------------------------------------------------------
    // Finding #1b: memoryExecutor discard-oldest handler — poll + re-execute
    // -----------------------------------------------------------------------

    /**
     * Verifies the {@code memoryExecutor} RejectedExecutionHandler (discard-oldest strategy):
     * when a task is rejected (queue full), the handler must:
     * (a) call {@link BlockingQueue#poll()} to discard the oldest enqueued task;
     * (b) call {@link ThreadPoolExecutor#execute(Runnable)} to re-submit the rejected task.
     *
     * Tested by extracting the handler from the live executor via reflection and driving it
     * with a mock {@link ThreadPoolExecutor} — avoids needing to actually saturate the pool.
     */
    @Test
    @SuppressWarnings("unchecked")
    void memoryExecutorHandler_discardOldest_pollsAndReExecutes() {
        ThreadPoolExecutor memExec = (ThreadPoolExecutor)
                ReflectionTestUtils.getField(service, "memoryExecutor");
        Assertions.assertNotNull(memExec, "memoryExecutor field must be present");

        RejectedExecutionHandler handler = memExec.getRejectedExecutionHandler();
        Assertions.assertNotNull(handler, "memoryExecutor must have a RejectedExecutionHandler");

        // Stub executor: not shut down, returns a mock queue.
        ThreadPoolExecutor mockExec = mock(ThreadPoolExecutor.class);
        BlockingQueue<Runnable> mockQueue = mock(BlockingQueue.class);
        when(mockExec.isShutdown()).thenReturn(false);
        when(mockExec.getQueue()).thenReturn(mockQueue);

        Runnable task = () -> {};
        handler.rejectedExecution(task, mockExec);

        // Discard-oldest: poll() must be called to remove the head of the queue.
        verify(mockQueue).poll();
        // Re-execute: execute(task) must be called to submit the originally-rejected task.
        verify(mockExec).execute(task);
    }

    /**
     * E4: Proves that recalled long-term memories are injected into the system prompt.
     * Stubs agentMemoryService.recall() to return a non-empty list, then captures the
     * LlmRequest passed to llmClient.generate() and asserts that the system prompt
     * contains the expected memory section header ("长期记忆") and the actual memory content.
     */
    @Test
    void answer_shouldInjectLongTermMemoryIntoSystemPrompt() {
        // Stub recall to return 1 memory note with recognizable content
        com.repolens.domain.entity.AgentMemoryEntity memNote =
                new com.repolens.domain.entity.AgentMemoryEntity();
        memNote.setId(42L);
        memNote.setContent("用户服务关键信息：UserService 实现在 service 包下");
        memNote.setKeywords("用户服务,UserService");
        memNote.setCreatedAt(java.time.LocalDateTime.now().minusDays(1));

        when(agentMemoryService.recall(any(), any(), any(), anyInt()))
                .thenReturn(List.of(memNote));

        when(ragRetrievalService.retrieve(eq(5L), eq(1L), eq("创建用户接口在哪里？"), eq(5)))
                .thenReturn(buildRagResult());
        when(llmClient.generate(any())).thenReturn(LlmResponse.builder()
                .content("创建入口位于 [src/main/java/com/example/UserController.java:12-30]。")
                .modelName("deepseek-chat")
                .promptTokens(150)
                .completionTokens(30)
                .costMs(20L)
                .success(true)
                .build());

        CodeAnswerRequest request = new CodeAnswerRequest();
        request.setQuestion("创建用户接口在哪里？");
        request.setTopK(5);

        service.answer(5L, 1L, request);

        // Capture the LlmRequest passed to llmClient.generate()
        ArgumentCaptor<com.repolens.llm.model.LlmRequest> captor =
                ArgumentCaptor.forClass(com.repolens.llm.model.LlmRequest.class);
        verify(llmClient).generate(captor.capture());
        com.repolens.llm.model.LlmRequest captured = captor.getValue();

        String systemPrompt = captured.getSystemPrompt();
        Assertions.assertNotNull(systemPrompt, "system prompt must be non-null");
        Assertions.assertTrue(systemPrompt.contains("长期记忆"),
                "system prompt must contain the long-term memory section header");
        Assertions.assertTrue(systemPrompt.contains("UserService 实现在 service 包下"),
                "system prompt must contain the actual memory content");
    }

    // -----------------------------------------------------------------------
    // J1: @提及上下文注入
    // -----------------------------------------------------------------------

    /**
     * 当 request 包含 mentions 时，mentionContextInjector.buildMentionEvidence 被调用，
     * 返回的证据文本被注入到 user prompt 中（通过 CodeAnswerPromptBuilder 的证据段）。
     */
    @Test
    void answer_withMentions_mentionEvidenceInjectedIntoPrompt() {
        com.repolens.domain.dto.chat.MentionDTO mention = new com.repolens.domain.dto.chat.MentionDTO();
        mention.setType("selection");
        mention.setValue(null);
        mention.setExtra("selected snippet content");

        String mentionEvidence = "[Mention-1]\nsource: @提及\ntype: selection\ncontent:\nselected snippet content\n\n";
        when(mentionContextInjector.buildMentionEvidence(eq(1L), eq(5L), any()))
                .thenReturn(mentionEvidence);
        when(agentRulesLoader.loadRules(eq(5L))).thenReturn(null);

        when(ragRetrievalService.retrieve(eq(5L), eq(1L), eq("创建用户接口在哪里？"), eq(5)))
                .thenReturn(buildRagResult());
        when(llmClient.generate(any())).thenReturn(LlmResponse.builder()
                .content("答案基于 mention 证据。")
                .modelName("deepseek-chat")
                .promptTokens(100)
                .completionTokens(10)
                .costMs(10L)
                .success(true)
                .build());

        CodeAnswerRequest request = new CodeAnswerRequest();
        request.setQuestion("创建用户接口在哪里？");
        request.setTopK(5);
        request.setMentions(List.of(mention));

        service.answer(5L, 1L, request);

        ArgumentCaptor<com.repolens.llm.model.LlmRequest> captor =
                ArgumentCaptor.forClass(com.repolens.llm.model.LlmRequest.class);
        verify(llmClient).generate(captor.capture());
        String userPrompt = captor.getValue().getUserPrompt();
        Assertions.assertTrue(userPrompt.contains("Mention-1"),
                "user prompt must contain mention evidence block");
        Assertions.assertTrue(userPrompt.contains("selected snippet content"),
                "user prompt must contain mention content");
    }

    /**
     * null mentions → mentionContextInjector.buildMentionEvidence 仍被调用（传 null），
     * 返回空字符串，不影响正常答案路径。
     */
    @Test
    void answer_noMentions_mentionEvidenceIsEmpty() {
        when(ragRetrievalService.retrieve(eq(5L), eq(1L), eq("创建用户接口在哪里？"), eq(5)))
                .thenReturn(buildRagResult());
        when(llmClient.generate(any())).thenReturn(LlmResponse.builder()
                .content("正常答案。")
                .modelName("deepseek-chat")
                .promptTokens(80)
                .completionTokens(8)
                .costMs(8L)
                .success(true)
                .build());

        CodeAnswerRequest request = new CodeAnswerRequest();
        request.setQuestion("创建用户接口在哪里？");
        request.setTopK(5);
        // no mentions set

        CodeAnswerVO response = service.answer(5L, 1L, request);

        Assertions.assertFalse(response.getDegraded());
        ArgumentCaptor<com.repolens.llm.model.LlmRequest> captor =
                ArgumentCaptor.forClass(com.repolens.llm.model.LlmRequest.class);
        verify(llmClient).generate(captor.capture());
        // without mention evidence, prompt should not contain Mention-1
        Assertions.assertFalse(captor.getValue().getUserPrompt().contains("[Mention-1]"));
    }

    // -----------------------------------------------------------------------
    // J2: AGENTS.md 项目规则注入
    // -----------------------------------------------------------------------

    /**
     * 当 agentRulesLoader 返回规则文本时，规则被注入到 system prompt。
     */
    @Test
    void answer_withAgentRules_rulesInjectedIntoSystemPrompt() {
        String rules = "# Project Rules\n- Always write tests\n- Use Java 17";
        when(agentRulesLoader.loadRules(eq(5L))).thenReturn(rules);
        when(mentionContextInjector.buildMentionEvidence(any(), any(), any())).thenReturn("");

        when(ragRetrievalService.retrieve(eq(5L), eq(1L), eq("创建用户接口在哪里？"), eq(5)))
                .thenReturn(buildRagResult());
        when(llmClient.generate(any())).thenReturn(LlmResponse.builder()
                .content("答案基于规则。")
                .modelName("deepseek-chat")
                .promptTokens(100)
                .completionTokens(10)
                .costMs(10L)
                .success(true)
                .build());

        CodeAnswerRequest request = new CodeAnswerRequest();
        request.setQuestion("创建用户接口在哪里？");
        request.setTopK(5);

        service.answer(5L, 1L, request);

        ArgumentCaptor<com.repolens.llm.model.LlmRequest> captor =
                ArgumentCaptor.forClass(com.repolens.llm.model.LlmRequest.class);
        verify(llmClient).generate(captor.capture());
        String systemPrompt = captor.getValue().getSystemPrompt();
        Assertions.assertTrue(systemPrompt.contains("Project Rules"),
                "system prompt must contain project rules section");
        Assertions.assertTrue(systemPrompt.contains("Always write tests"),
                "system prompt must contain the actual rules content");
    }

    /**
     * 当 agentRulesLoader 返回 null（文件不存在）时，system prompt 不含规则段，正常答案路径不变。
     */
    @Test
    void answer_noAgentRules_systemPromptUnchanged() {
        when(agentRulesLoader.loadRules(eq(5L))).thenReturn(null);

        when(ragRetrievalService.retrieve(eq(5L), eq(1L), eq("创建用户接口在哪里？"), eq(5)))
                .thenReturn(buildRagResult());
        when(llmClient.generate(any())).thenReturn(LlmResponse.builder()
                .content("正常答案。")
                .modelName("deepseek-chat")
                .promptTokens(80)
                .completionTokens(8)
                .costMs(8L)
                .success(true)
                .build());

        CodeAnswerRequest request = new CodeAnswerRequest();
        request.setQuestion("创建用户接口在哪里？");
        request.setTopK(5);

        service.answer(5L, 1L, request);

        ArgumentCaptor<com.repolens.llm.model.LlmRequest> captor =
                ArgumentCaptor.forClass(com.repolens.llm.model.LlmRequest.class);
        verify(llmClient).generate(captor.capture());
        String systemPrompt = captor.getValue().getSystemPrompt();
        Assertions.assertFalse(systemPrompt.contains("Project Rules"),
                "system prompt must not contain project rules section when none loaded");
    }

    // -----------------------------------------------------------------------
    // Regression guards for commit e4de11b — ask-mode write-request redirect
    // -----------------------------------------------------------------------

    /**
     * Ask-mode + imperative write request → must short-circuit before RAG retrieval and return
     * the "switch to code mode" redirect answer with degraded=true.
     *
     * Regression guard: before the fix, such requests fell through to RAG+LLM, wasting a call
     * and returning a confusing "insufficient evidence" answer instead of actionable guidance.
     */
    @Test
    void answer_askMode_writeRequest_shortCircuitsBeforeRetrieval() {
        // A question that clearly matches WRITE_INTENT (pattern A: 你 + 新建 with 0 chars between).
        String writeQuestion = "你新建一个model文件夹，然后给我写一个个人博客的实现";

        CodeAnswerRequest request = new CodeAnswerRequest();
        request.setQuestion(writeQuestion);
        request.setTopK(5);
        // mode is null → ask mode (isCodeMode returns false)

        CodeAnswerVO response = service.answer(5L, 1L, request);

        String expectedAnswer = (String) ReflectionTestUtils.getField(
                CodeAnswerServiceImpl.class, "SWITCH_TO_CODE_ANSWER");
        Assertions.assertEquals(expectedAnswer, response.getAnswer(),
                "write request in ask mode must return the code-mode redirect message");
        Assertions.assertTrue(response.getDegraded());
        Assertions.assertEquals("write request in ask mode", response.getDegradeReason());

        // The short-circuit happens before retrieve(), so RAG must never be called.
        verify(ragRetrievalService, never()).retrieve(any(), any(), any(), anyInt());
    }

    /**
     * Ask-mode + normal interrogative question → must NOT trigger the write-redirect.
     * Guards against false positives in looksLikeWriteRequest(): "创建用户接口在哪里？"
     * contains the verb 创建 but is purely a lookup question (no imperative prefix).
     */
    @Test
    void answer_askMode_interrogativeQuestion_doesNotShortCircuit() {
        String question = "创建用户接口在哪里？";
        when(ragRetrievalService.retrieve(eq(5L), eq(1L), eq(question), eq(5)))
                .thenReturn(buildRagResult());
        when(llmClient.generate(any())).thenReturn(LlmResponse.builder()
                .content("创建入口位于 UserController。")
                .modelName("deepseek-chat")
                .promptTokens(80).completionTokens(8).costMs(5L).success(true).build());

        CodeAnswerRequest request = new CodeAnswerRequest();
        request.setQuestion(question);
        request.setTopK(5);
        // no mode → ask mode

        CodeAnswerVO response = service.answer(5L, 1L, request);

        String switchAnswer = (String) ReflectionTestUtils.getField(
                CodeAnswerServiceImpl.class, "SWITCH_TO_CODE_ANSWER");
        Assertions.assertNotEquals(switchAnswer, response.getAnswer(),
                "interrogative question must not trigger the write-redirect");
        // RAG retrieval must have been called (no short-circuit).
        verify(ragRetrievalService).retrieve(eq(5L), eq(1L), eq(question), eq(5));
    }

    /**
     * Ask-mode + another normal question ("UserService 有哪些方法？") must not short-circuit.
     * "UserService" is a proper noun used in a query, not an imperative write command.
     */
    @Test
    void answer_askMode_serviceQueryQuestion_doesNotShortCircuit() {
        String question = "UserService 有哪些方法？";
        // RAG returns empty → early return with "insufficient evidence" (not the write-redirect).
        when(ragRetrievalService.retrieve(eq(5L), eq(1L), eq(question), eq(5)))
                .thenReturn(RagSearchResultVO.builder()
                        .repoId(5L).query(question).topK(5).hitCount(0)
                        .degraded(false).degradeReason(null).results(List.of())
                        .build());

        CodeAnswerRequest request = new CodeAnswerRequest();
        request.setQuestion(question);
        request.setTopK(5);

        CodeAnswerVO response = service.answer(5L, 1L, request);

        String switchAnswer = (String) ReflectionTestUtils.getField(
                CodeAnswerServiceImpl.class, "SWITCH_TO_CODE_ANSWER");
        Assertions.assertNotEquals(switchAnswer, response.getAnswer(),
                "pure query question must not trigger the write-redirect");
        // RAG retrieve must have been called — the short-circuit does not fire.
        verify(ragRetrievalService).retrieve(eq(5L), eq(1L), eq(question), eq(5));
    }

    /**
     * Code-mode + write request → must NOT short-circuit.  Code mode has write tools and
     * should reach RAG retrieval normally.  The write-redirect is only for ask-mode.
     */
    @Test
    void answer_codeMode_writeRequest_doesNotShortCircuit() {
        String writeQuestion = "你新建一个model文件夹，然后给我写一个个人博客的实现";
        // Stub RAG to return zero results; the service will produce "insufficient evidence" (not
        // the write-redirect), confirming that code mode bypasses the ask-mode short-circuit.
        when(ragRetrievalService.retrieve(eq(5L), eq(1L), eq(writeQuestion), anyInt()))
                .thenReturn(RagSearchResultVO.builder()
                        .repoId(5L).query(writeQuestion).topK(5).hitCount(0)
                        .degraded(false).degradeReason(null).results(List.of())
                        .build());

        CodeAnswerRequest request = new CodeAnswerRequest();
        request.setQuestion(writeQuestion);
        request.setTopK(5);
        request.setMode("code");  // code mode → isCodeMode returns true → short-circuit skipped

        CodeAnswerVO response = service.answer(5L, 1L, request);

        String switchAnswer = (String) ReflectionTestUtils.getField(
                CodeAnswerServiceImpl.class, "SWITCH_TO_CODE_ANSWER");
        Assertions.assertNotEquals(switchAnswer, response.getAnswer(),
                "code mode must bypass the ask-mode write-redirect");
        // In code mode, RAG retrieval must proceed (no short-circuit).
        verify(ragRetrievalService).retrieve(eq(5L), eq(1L), eq(writeQuestion), anyInt());
    }

    // -----------------------------------------------------------------------
    // Regression guards for commit 1783b10 — code-mode write prompt + no-evidence refusal bypass
    // -----------------------------------------------------------------------

    /**
     * Regression guard: in CODE mode the agent's noEvidenceAtAll refusal (which fires in ask mode
     * when RAG returns nothing and the agent also discovers nothing) must be skipped.
     *
     * The fix in doStreamAgentTrue guards the refusal with !codeMode so that code mode is never
     * blocked from writing new code just because the repository has no existing evidence for it.
     *
     * Setup: agentEnabled=true, request.mode="code", empty RAG results → isWeakEvidence=true →
     * routes to streamAgent → doStreamAgentTrue. MockAgentResult has empty discoveredReferences.
     * If the guard was absent, noEvidenceAtAll would be true → ASSISTANT message = NO_EVIDENCE_ANSWER.
     * With the guard, noEvidenceAtAll=false → ASSISTANT message = agent answer.
     */
    @Test
    void answerStream_codeMode_emptyEvidence_agentDoesNotFireNoEvidenceRefusal() throws Exception {
        ReflectionTestUtils.setField(service, "agentEnabled", true);
        // codePlanningEnabled defaults to false (no Spring DI) — skip planning step

        String question = "新建一个 UserRepository 类";
        // Empty RAG → isWeakEvidence=true → streamAgent path chosen
        when(ragRetrievalService.retrieve(eq(5L), eq(1L), eq(question), any()))
                .thenReturn(RagSearchResultVO.builder()
                        .repoId(5L).query(question).topK(5).hitCount(0)
                        .degraded(false).degradeReason(null).results(List.of())
                        .build());

        // Agent returns an answer with zero discovered references — worst-case for noEvidenceAtAll check
        AgentLoopExecutor.AgentResult codeResult = AgentLoopExecutor.AgentResult.builder()
                .answer("已新建 UserRepository.java，实现了基本 CRUD 接口。")
                .iterations(1).toolCallCount(0).hitMaxIterations(false)
                .steps(List.of()).discoveredReferences(List.of())
                .build();
        when(agentLoopExecutor.runStreaming(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(codeResult);

        CountDownLatch done = new CountDownLatch(1);
        SseEmitter emitter = new SseEmitter(5000L) {
            @Override
            public void complete() {
                super.complete();
                done.countDown();
            }
            @Override
            public void completeWithError(Throwable failure) {
                super.completeWithError(failure);
                done.countDown();
            }
        };

        CodeAnswerRequest request = new CodeAnswerRequest();
        request.setQuestion(question);
        request.setTopK(5);
        request.setMode("code");  // CODE mode → isCodeMode=true → noEvidenceAtAll guard bypassed

        service.answerStream(5L, 1L, request, emitter);
        Assertions.assertTrue(done.await(3, TimeUnit.SECONDS),
                "SSE emitter should complete within 3 s");

        // runStreaming must have been called (agent ran, not short-circuited before the loop)
        verify(agentLoopExecutor, times(1))
                .runStreaming(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any());

        // The ASSISTANT message persisted to the DB must be the agent answer, NOT the no-evidence refusal.
        String noEvidenceAnswer = (String) ReflectionTestUtils.getField(
                CodeAnswerServiceImpl.class, "NO_EVIDENCE_ANSWER");
        ArgumentCaptor<ChatMessageEntity> msgCaptor = ArgumentCaptor.forClass(ChatMessageEntity.class);
        // 2 inserts: USER message (from prepareAnswer) + ASSISTANT message (from doStreamAgentTrue)
        verify(chatMessageMapper, times(2)).insert(msgCaptor.capture());
        ChatMessageEntity assistantMsg = msgCaptor.getAllValues().get(1);
        Assertions.assertEquals("ASSISTANT", assistantMsg.getRole());
        Assertions.assertNotEquals(noEvidenceAnswer, assistantMsg.getContent(),
                "code mode must NOT produce the no-evidence refusal answer");
        Assertions.assertEquals("已新建 UserRepository.java，实现了基本 CRUD 接口。",
                assistantMsg.getContent(),
                "ASSISTANT message in code mode must be the actual agent answer");
    }

    /**
     * Backward-compat guard: ask-mode non-agent no-evidence refusal still fires.
     * Guards that the !codeMode fix did not accidentally break the existing single-shot ask-mode path.
     * (This is a re-assertion of the original answer_shouldRefuseWhenNoEvidence scenario, kept
     * adjacent to the code-mode companion for clear regression pairing.)
     */
    @Test
    void answer_askMode_emptyEvidence_noEvidenceRefusalStillFires() {
        // agentEnabled=false (default in setUp) + empty RAG + no history → no-evidence early return
        when(ragRetrievalService.retrieve(eq(5L), eq(1L), eq("创建用户接口在哪里？"), eq(5)))
                .thenReturn(RagSearchResultVO.builder()
                        .repoId(5L).query("创建用户接口在哪里？").topK(5).hitCount(0)
                        .degraded(false).degradeReason(null).results(List.of())
                        .build());

        CodeAnswerRequest request = new CodeAnswerRequest();
        request.setQuestion("创建用户接口在哪里？");
        request.setTopK(5);
        // mode is null → ask mode → !isCodeMode

        CodeAnswerVO response = service.answer(5L, 1L, request);

        Assertions.assertTrue(response.getDegraded(),
                "ask mode with no evidence must degrade");
        Assertions.assertEquals("insufficient evidence", response.getDegradeReason());
        Assertions.assertTrue(response.getAnswer().contains("无法可靠回答"),
                "ask mode must produce the no-evidence refusal answer");
        verify(llmClient, never()).generate(any());
    }

    private RagSearchResultVO buildRagResult() {
        return RagSearchResultVO.builder()
                .repoId(5L)
                .query("创建用户接口在哪里？")
                .topK(5)
                .hitCount(1)
                .degraded(false)
                .degradeReason(null)
                .results(List.of(RagChunkVO.builder()
                        .chunkId("chunk-1")
                        .filePath("src/main/java/com/example/UserController.java")
                        .chunkType("METHOD")
                        .language("java")
                        .startLine(12)
                        .endLine(30)
                        .score(0.98f)
                        .content("public User createUser(UserCreateRequest request) { ... }")
                        .contentPreview("UserController createUser")
                        .build()))
                .build();
    }
}
