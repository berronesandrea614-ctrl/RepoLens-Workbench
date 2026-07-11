package com.repolens.service.impl.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.domain.vo.AgentStepVO;
import com.repolens.llm.LlmClient;
import com.repolens.llm.StreamWithToolsListener;
import com.repolens.llm.model.LlmMessage;
import com.repolens.llm.model.LlmRequest;
import com.repolens.llm.model.LlmResponse;
import com.repolens.llm.model.ToolCall;
import com.repolens.llm.model.ToolDefinition;
import com.repolens.mapper.LlmCallLogMapper;
import com.repolens.service.ToolInvokeService;
import org.junit.jupiter.api.Test;

import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentLoopExecutor.runStreaming 的单元测试。
 * 验证：答案轮缓冲 flush、step 事件顺序、最终答案正确返回。
 */
class AgentLoopExecutorStreamingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentLoopExecutor makeExecutor(LlmClient llmClient,
                                           ToolInvokeService toolInvokeService,
                                           LlmCallLogMapper llmCallLogMapper) {
        com.repolens.hooks.HookDispatcher hookDispatcher = mock(com.repolens.hooks.HookDispatcher.class);
        when(hookDispatcher.dispatchPreToolUse(any())).thenReturn(com.repolens.hooks.HookResult.allow());
        AgentLoopExecutor exec = new AgentLoopExecutor(llmClient, toolInvokeService, MAPPER,
                llmCallLogMapper, null, mock(com.repolens.service.support.AgentControlToolHandler.class),
                new com.repolens.service.support.TodoState(),
                mock(com.repolens.service.support.AgentTaskDispatcher.class),
                mock(com.repolens.mapper.AgentRunPlanMapper.class),
                mock(com.repolens.service.support.context.ContextManager.class),
                hookDispatcher,
                new com.repolens.service.support.SteeringQueue());
        ReflectionTestUtils.setField(exec, "maxToolTurns", 8);
        ReflectionTestUtils.setField(exec, "wallClockBudgetMs", 90000);
        return exec;
    }

    /** 配置 LlmClient mock 使其在 generateStreamWithTools 调用时运行指定的逻辑。 */
    private static void stubStreamWithTools(LlmClient llmClient, java.util.function.Consumer<StreamWithToolsListener>... rounds) {
        int[] callCount = {0};
        doAnswer(invocation -> {
            StreamWithToolsListener listener = invocation.getArgument(1);
            int round = callCount[0]++;
            if (round < rounds.length) {
                rounds[round].accept(listener);
            }
            return null;
        }).when(llmClient).generateStreamWithTools(any(LlmRequest.class), any(StreamWithToolsListener.class));
    }

    // -----------------------------------------------------------------------
    // TC-1: 最终答案轮（无 tool_calls）缓冲 token 被 flush 为 onAnswerToken
    // -----------------------------------------------------------------------

    @Test
    void answerRound_bufferFlushedAsAnswerTokens() {
        LlmClient llmClient = mock(LlmClient.class);
        ToolInvokeService toolInvokeService = mock(ToolInvokeService.class);
        LlmCallLogMapper llmCallLogMapper = mock(LlmCallLogMapper.class);

        // 首轮返回 tool_calls，第二轮（答案轮）返回内容 token + 无 tool_calls
        ToolCall call = ToolCall.builder().id("c1").name("searchCodeChunks")
                .arguments(Map.of("query", "user")).build();

        stubStreamWithTools(llmClient,
                // 轮次 1: 发出工具调用
                listener -> {
                    // 中间思考文本（应被丢弃）
                    listener.onContentToken("让我查一下…");
                    listener.onToolCallStart("searchCodeChunks");
                    listener.onDone(LlmResponse.builder()
                            .content("让我查一下…")
                            .toolCalls(List.of(call))
                            .success(true).build());
                },
                // 轮次 2: 最终答案（token 应 flush 给 streamListener）
                listener -> {
                    listener.onContentToken("答案 A");
                    listener.onContentToken("答案 B");
                    listener.onDone(LlmResponse.builder()
                            .content("答案 A答案 B")
                            .toolCalls(List.of())
                            .success(true).build());
                }
        );

        when(toolInvokeService.invoke(anyLong(), anyLong(), any(), anyString(), anyMap(), any(), any()))
                .thenReturn("tool result");

        AgentLoopExecutor exec = makeExecutor(llmClient, toolInvokeService, llmCallLogMapper);

        List<String> answerTokens = new ArrayList<>();
        List<AgentStepVO> stepsReceived = new ArrayList<>();

        AgentLoopExecutor.AgentResult result = exec.runStreaming(
                1L, 5L, 100L, List.of(), List.<ToolDefinition>of(), "model", 5, 15000,
                new AgentLoopExecutor.AgentStreamListener() {
                    @Override
                    public void onStep(AgentStepVO step) {
                        stepsReceived.add(step);
                    }

                    @Override
                    public void onAnswerToken(String token) {
                        answerTokens.add(token);
                    }
                });

        // 答案 token 只来自最终轮（无 tool_calls 轮）
        assertThat(answerTokens).containsExactly("答案 A", "答案 B");

        // 中间思考文本（来自工具轮）不在答案 token 里
        assertThat(answerTokens).doesNotContain("让我查一下…");

        // step 事件被触发一次
        assertThat(stepsReceived).hasSize(1);
        assertThat(stepsReceived.get(0).getToolName()).isEqualTo("searchCodeChunks");

        // 最终答案正确
        assertThat(result.getAnswer()).isEqualTo("答案 A答案 B");
    }

    // -----------------------------------------------------------------------
    // TC-2: step 事件按工具执行完成顺序发出（先 step 后下一轮 LLM 调用）
    // -----------------------------------------------------------------------

    @Test
    void stepEventsEmittedInOrder_afterToolExecution() {
        LlmClient llmClient = mock(LlmClient.class);
        ToolInvokeService toolInvokeService = mock(ToolInvokeService.class);
        LlmCallLogMapper llmCallLogMapper = mock(LlmCallLogMapper.class);

        ToolCall call1 = ToolCall.builder().id("c1").name("searchCodeChunks")
                .arguments(Map.of("query", "a")).build();
        ToolCall call2 = ToolCall.builder().id("c2").name("getFileContent")
                .arguments(Map.of("filePath", "Foo.java")).build();

        stubStreamWithTools(llmClient,
                listener -> {
                    listener.onToolCallStart("searchCodeChunks");
                    listener.onDone(LlmResponse.builder()
                            .content("").toolCalls(List.of(call1)).success(true).build());
                },
                listener -> {
                    listener.onToolCallStart("getFileContent");
                    listener.onDone(LlmResponse.builder()
                            .content("").toolCalls(List.of(call2)).success(true).build());
                },
                listener -> {
                    listener.onContentToken("final answer");
                    listener.onDone(LlmResponse.builder()
                            .content("final answer").toolCalls(List.of()).success(true).build());
                }
        );

        when(toolInvokeService.invoke(anyLong(), anyLong(), any(), anyString(), anyMap(), any(), any()))
                .thenReturn("result");

        AgentLoopExecutor exec = makeExecutor(llmClient, toolInvokeService, llmCallLogMapper);

        List<String> eventOrder = new ArrayList<>();

        exec.runStreaming(1L, 5L, 100L, List.of(), List.<ToolDefinition>of(), "model", 5, 15000,
                new AgentLoopExecutor.AgentStreamListener() {
                    @Override
                    public void onStep(AgentStepVO step) {
                        eventOrder.add("step:" + step.getToolName());
                    }

                    @Override
                    public void onAnswerToken(String token) {
                        eventOrder.add("token:" + token);
                    }
                });

        // 步骤按执行顺序发出
        assertThat(eventOrder).containsExactly(
                "step:searchCodeChunks",
                "step:getFileContent",
                "token:final answer"
        );

        // 工具被调用两次
        verify(toolInvokeService, times(2)).invoke(anyLong(), anyLong(), any(), anyString(), anyMap(), any(), any());
    }

    // -----------------------------------------------------------------------
    // TC-3: 直接答案轮（首轮无 tool_calls）→ 立即 flush 缓冲 token
    // -----------------------------------------------------------------------

    @Test
    void directAnswerRound_immediatelyFlushesTokenBuffer() {
        LlmClient llmClient = mock(LlmClient.class);
        ToolInvokeService toolInvokeService = mock(ToolInvokeService.class);
        LlmCallLogMapper llmCallLogMapper = mock(LlmCallLogMapper.class);

        stubStreamWithTools(llmClient,
                listener -> {
                    listener.onContentToken("chunk1");
                    listener.onContentToken("chunk2");
                    listener.onDone(LlmResponse.builder()
                            .content("chunk1chunk2")
                            .toolCalls(List.of())
                            .success(true).build());
                }
        );

        AgentLoopExecutor exec = makeExecutor(llmClient, toolInvokeService, llmCallLogMapper);

        List<String> tokens = new ArrayList<>();
        List<AgentStepVO> steps = new ArrayList<>();

        AgentLoopExecutor.AgentResult result = exec.runStreaming(
                1L, 5L, 100L, List.of(), List.<ToolDefinition>of(), "model", 3, 15000,
                new AgentLoopExecutor.AgentStreamListener() {
                    @Override
                    public void onStep(AgentStepVO step) { steps.add(step); }

                    @Override
                    public void onAnswerToken(String token) { tokens.add(token); }
                });

        assertThat(tokens).containsExactly("chunk1", "chunk2");
        assertThat(steps).isEmpty();  // 无工具调用，无 step 事件
        assertThat(result.getAnswer()).isEqualTo("chunk1chunk2");
        assertThat(result.isHitMaxIterations()).isFalse();
        assertThat(result.getToolCallCount()).isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // TC-4: llm_call_log 在 runStreaming 里仍然落库（audit 不退化）
    // -----------------------------------------------------------------------

    @Test
    void runStreaming_persistsLlmCallLogPerCall() {
        LlmClient llmClient = mock(LlmClient.class);
        ToolInvokeService toolInvokeService = mock(ToolInvokeService.class);
        LlmCallLogMapper llmCallLogMapper = mock(LlmCallLogMapper.class);

        ToolCall call = ToolCall.builder().id("c1").name("searchCodeChunks")
                .arguments(Map.of("query", "x")).build();

        stubStreamWithTools(llmClient,
                listener -> listener.onDone(LlmResponse.builder()
                        .content("").toolCalls(List.of(call)).modelName("m1")
                        .promptTokens(10).completionTokens(5).costMs(50L).success(true).build()),
                listener -> listener.onDone(LlmResponse.builder()
                        .content("final").toolCalls(List.of()).modelName("m1")
                        .promptTokens(20).completionTokens(10).costMs(80L).success(true).build())
        );

        when(toolInvokeService.invoke(anyLong(), anyLong(), any(), anyString(), anyMap(), any(), any()))
                .thenReturn(List.of());

        AgentLoopExecutor exec = makeExecutor(llmClient, toolInvokeService, llmCallLogMapper);

        exec.runStreaming(1L, 5L, 202L, List.of(), List.<ToolDefinition>of(), "m1", 5, 15000,
                new AgentLoopExecutor.AgentStreamListener() {
                    @Override public void onStep(AgentStepVO step) {}
                    @Override public void onAnswerToken(String token) {}
                });

        // 两次 LLM 调用 → 两条 llm_call_log 审计行
        verify(llmCallLogMapper, times(2)).insert(any(com.repolens.domain.entity.LlmCallLogEntity.class));
    }
}
