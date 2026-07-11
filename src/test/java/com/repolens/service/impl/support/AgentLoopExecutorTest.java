package com.repolens.service.impl.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.domain.entity.LlmCallLogEntity;
import com.repolens.llm.LlmClient;
import com.repolens.llm.LlmClientException;
import com.repolens.llm.model.LlmMessage;
import com.repolens.llm.model.LlmRequest;
import com.repolens.llm.model.LlmResponse;
import com.repolens.llm.model.ToolCall;
import com.repolens.llm.model.ToolDefinition;
import com.repolens.mapper.LlmCallLogMapper;
import com.repolens.service.ToolInvokeService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentLoopExecutor 的单元测试：聚焦审计修复的四个逻辑要点。
 *
 * FIX 1: 每次真实 LLM 调用后落一条 llm_call_log（不再是末尾合成汇总条目）。
 * FIX 2: 同一 (toolName, argsHash) 在本次 run 内只真正执行一次，重复调用跳过。
 * FIX 3: 命中最大轮数且末轮仍带 tool_calls 时，思考文本不得泄露成最终答案（应为空以触发上层兜底）。
 * FIX 4: 工具异常消息含反斜杠时，回填给模型的错误 JSON 仍是合法 JSON（正确转义）。
 * FIX 5: 上下文总字符超出预算时，最旧的 tool 观察被压缩，system + 最近 2 条观察保持完整。
 */
class AgentLoopExecutorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Helper: build an executor with all dependencies mocked. */
    private AgentLoopExecutor executor(LlmClient llmClient, ToolInvokeService toolInvokeService,
                                       LlmCallLogMapper llmCallLogMapper) {
        com.repolens.hooks.HookDispatcher hookDispatcher = mock(com.repolens.hooks.HookDispatcher.class);
        when(hookDispatcher.dispatchPreToolUse(any())).thenReturn(com.repolens.hooks.HookResult.allow());
        AgentLoopExecutor exec = new AgentLoopExecutor(llmClient, toolInvokeService, objectMapper,
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

    private ToolCall sampleCall() {
        return ToolCall.builder()
                .id("call-1")
                .name("searchCodeChunks")
                .arguments(Map.of("query", "user"))
                .build();
    }

    // -----------------------------------------------------------------------
    // FIX 1: real llm_call_log per LLM call
    // -----------------------------------------------------------------------

    @Test
    void eachLlmCallProducesOneLlmCallLog() {
        LlmClient llmClient = mock(LlmClient.class);
        ToolInvokeService toolInvokeService = mock(ToolInvokeService.class);
        LlmCallLogMapper llmCallLogMapper = mock(LlmCallLogMapper.class);

        // Two LLM calls: first returns a tool call, second returns the final answer.
        when(llmClient.generate(any()))
                .thenReturn(LlmResponse.builder().content("thinking…").toolCalls(List.of(sampleCall()))
                        .modelName("m1").promptTokens(10).completionTokens(5).costMs(50L).success(true).build())
                .thenReturn(LlmResponse.builder().content("final answer").toolCalls(List.of())
                        .modelName("m1").promptTokens(20).completionTokens(10).costMs(80L).success(true).build());
        when(toolInvokeService.invoke(anyLong(), anyLong(), any(), anyString(), anyMap(), any(), any()))
                .thenReturn(List.of());

        AgentLoopExecutor exec = executor(llmClient, toolInvokeService, llmCallLogMapper);
        AgentLoopExecutor.AgentResult result = exec.run(
                1L, 5L, 202L, List.of(), List.<ToolDefinition>of(), "m1", 5, 15000);

        // Two LLM calls → two llm_call_log inserts.
        verify(llmCallLogMapper, times(2)).insert(any(com.repolens.domain.entity.LlmCallLogEntity.class));
        assertThat(result.getAnswer()).isEqualTo("final answer");
    }

    // -----------------------------------------------------------------------
    // FIX 2: (tool, args) dedup — second call is skipped
    // -----------------------------------------------------------------------

    @Test
    void duplicateToolCall_isSkippedWithFixedObservation() {
        LlmClient llmClient = mock(LlmClient.class);
        ToolInvokeService toolInvokeService = mock(ToolInvokeService.class);
        LlmCallLogMapper llmCallLogMapper = mock(LlmCallLogMapper.class);

        // Same tool + same args in both iterations (different call ids, but same name+args).
        ToolCall firstCall = ToolCall.builder().id("c1").name("searchCodeChunks")
                .arguments(Map.of("query", "user")).build();
        ToolCall dupCall = ToolCall.builder().id("c2").name("searchCodeChunks")
                .arguments(Map.of("query", "user")).build();

        when(llmClient.generate(any()))
                .thenReturn(LlmResponse.builder().content("").toolCalls(List.of(firstCall)).build())
                .thenReturn(LlmResponse.builder().content("").toolCalls(List.of(dupCall)).build())
                .thenReturn(LlmResponse.builder().content("done").toolCalls(List.of()).build());
        when(toolInvokeService.invoke(anyLong(), anyLong(), any(), anyString(), anyMap(), any(), any()))
                .thenReturn("result-1");

        AgentLoopExecutor exec = executor(llmClient, toolInvokeService, llmCallLogMapper);
        AgentLoopExecutor.AgentResult result = exec.run(
                1L, 5L, 202L, List.of(), List.<ToolDefinition>of(), "mock", 5, 15000);

        // Real invocation only ONCE — the duplicate is skipped.
        verify(toolInvokeService, times(1)).invoke(anyLong(), anyLong(), any(), anyString(), anyMap(), any(), any());

        // Steps: 2 total (first real + second skipped).
        assertThat(result.getSteps()).hasSize(2);
        assertThat(result.getSteps().get(1).getObservation()).contains("重复调用已跳过");

        // Final answer reached normally.
        assertThat(result.getAnswer()).isEqualTo("done");
        assertThat(result.isHitMaxIterations()).isFalse();
    }

    // -----------------------------------------------------------------------
    // FIX 3: max-iterations trailing tool_calls → empty answer (not thinking text)
    // -----------------------------------------------------------------------

    @Test
    void hitMaxIterationsWithTrailingToolCalls_yieldsEmptyAnswerNotThinkingText() {
        LlmClient llmClient = mock(LlmClient.class);
        ToolInvokeService toolInvokeService = mock(ToolInvokeService.class);
        LlmCallLogMapper llmCallLogMapper = mock(LlmCallLogMapper.class);

        // 每一轮 LLM 都返回"思考文本 + tool_calls"，即模型始终想继续 → 必然命中 maxIterations。
        when(llmClient.generate(any())).thenReturn(LlmResponse.builder()
                .content("让我查一下相关代码……")
                .toolCalls(List.of(sampleCall()))
                .build());
        when(toolInvokeService.invoke(anyLong(), anyLong(), any(), anyString(), anyMap(), any(), any()))
                .thenReturn(List.of());

        AgentLoopExecutor exec = executor(llmClient, toolInvokeService, llmCallLogMapper);
        AgentLoopExecutor.AgentResult result = exec.run(
                1L, 5L, 202L, List.of(), List.<ToolDefinition>of(), "mock", 3, 15000);

        assertThat(result.isHitMaxIterations()).isTrue();
        // 关键断言：答案为空，绝不是思考文本，交由上层证据摘要兜底。
        assertThat(result.getAnswer()).isEmpty();
        assertThat(result.getIterations()).isEqualTo(3);
    }

    @Test
    void normalTermination_returnsFinalAnswer() {
        LlmClient llmClient = mock(LlmClient.class);
        ToolInvokeService toolInvokeService = mock(ToolInvokeService.class);
        LlmCallLogMapper llmCallLogMapper = mock(LlmCallLogMapper.class);

        // 首轮就无 tool_calls → 正常终止，返回最终答案。
        when(llmClient.generate(any())).thenReturn(LlmResponse.builder()
                .content("最终答案")
                .toolCalls(List.of())
                .build());

        AgentLoopExecutor exec = executor(llmClient, toolInvokeService, llmCallLogMapper);
        AgentLoopExecutor.AgentResult result = exec.run(
                1L, 5L, 202L, List.of(), List.<ToolDefinition>of(), "mock", 3, 15000);

        assertThat(result.isHitMaxIterations()).isFalse();
        assertThat(result.getAnswer()).isEqualTo("最终答案");
    }

    // -----------------------------------------------------------------------
    // FIX 4: backslash in tool error → valid JSON observation
    // -----------------------------------------------------------------------

    @Test
    void toolFailureWithBackslashMessage_producesValidJson() throws Exception {
        LlmClient llmClient = mock(LlmClient.class);
        ToolInvokeService toolInvokeService = mock(ToolInvokeService.class);
        LlmCallLogMapper llmCallLogMapper = mock(LlmCallLogMapper.class);

        // 单轮：LLM 请求一次工具，随后终止；工具抛含 Windows 路径反斜杠的异常。
        when(llmClient.generate(any()))
                .thenReturn(LlmResponse.builder()
                        .content("")
                        .toolCalls(List.of(sampleCall()))
                        .build())
                .thenReturn(LlmResponse.builder().content("done").toolCalls(List.of()).build());
        when(toolInvokeService.invoke(anyLong(), anyLong(), any(), anyString(), anyMap(), any(), any()))
                .thenThrow(new RuntimeException("bad path C:\\foo\\bar and regex \\d+"));

        AgentLoopExecutor exec = executor(llmClient, toolInvokeService, llmCallLogMapper);
        AgentLoopExecutor.AgentResult result = exec.run(
                1L, 5L, 202L, List.of(), List.<ToolDefinition>of(), "mock", 3, 15000);

        // 步骤里记录的 observation 必须是合法可解析 JSON（反斜杠已正确转义）。
        assertThat(result.getSteps()).hasSize(1);
        String observation = result.getSteps().get(0).getObservation();
        JsonNode parsed = objectMapper.readTree(observation); // 非法 JSON 会抛异常
        assertThat(parsed.path("error").asText()).contains("C:\\foo\\bar");
    }

    // -----------------------------------------------------------------------
    // FIX 5: context budget guard — old observations are compressed
    // -----------------------------------------------------------------------

    @Test
    void contextBudget_callsContextManagerCompactDuringLoop() {
        LlmClient llmClient = mock(LlmClient.class);
        ToolInvokeService toolInvokeService = mock(ToolInvokeService.class);
        LlmCallLogMapper llmCallLogMapper = mock(LlmCallLogMapper.class);
        com.repolens.service.support.context.ContextManager mockCtxManager =
                mock(com.repolens.service.support.context.ContextManager.class);
        when(mockCtxManager.compact(any(), anyInt())).thenReturn(false);

        ToolCall call1 = ToolCall.builder().id("c1").name("searchCodeChunks")
                .arguments(Map.of("query", "a")).build();

        when(llmClient.generate(any()))
                .thenReturn(LlmResponse.builder().content("").toolCalls(List.of(call1)).build())
                .thenReturn(LlmResponse.builder().content("final").toolCalls(List.of()).build());

        when(toolInvokeService.invoke(anyLong(), anyLong(), any(), anyString(), anyMap(), any(), any()))
                .thenReturn("result");

        com.repolens.hooks.HookDispatcher hd = mock(com.repolens.hooks.HookDispatcher.class);
        when(hd.dispatchPreToolUse(any())).thenReturn(com.repolens.hooks.HookResult.allow());
        AgentLoopExecutor exec = new AgentLoopExecutor(llmClient, toolInvokeService, objectMapper,
                llmCallLogMapper, null, mock(com.repolens.service.support.AgentControlToolHandler.class),
                new com.repolens.service.support.TodoState(),
                mock(com.repolens.service.support.AgentTaskDispatcher.class),
                mock(com.repolens.mapper.AgentRunPlanMapper.class),
                mockCtxManager,
                hd,
                new com.repolens.service.support.SteeringQueue());
        ReflectionTestUtils.setField(exec, "maxToolTurns", 8);
        ReflectionTestUtils.setField(exec, "wallClockBudgetMs", 90000);

        exec.run(1L, 5L, 202L, List.of(), List.<ToolDefinition>of(), "mock", 5, 15000);

        verify(mockCtxManager, atLeastOnce()).compact(any(), anyInt());
    }

    // -----------------------------------------------------------------------
    // FIX 6: failed llm.generate() persists a success=false llm_call_log row
    // -----------------------------------------------------------------------

    @Test
    void failedLlmGenerate_persistsSuccessFalseLogRow() {
        LlmClient llmClient = mock(LlmClient.class);
        ToolInvokeService toolInvokeService = mock(ToolInvokeService.class);
        LlmCallLogMapper llmCallLogMapper = mock(LlmCallLogMapper.class);

        // LLM call throws a client exception on the very first generate() call.
        when(llmClient.generate(any())).thenThrow(new LlmClientException("TIMEOUT", "request timed out"));

        AgentLoopExecutor exec = executor(llmClient, toolInvokeService, llmCallLogMapper);

        // The exception must propagate to the caller.
        assertThatThrownBy(() -> exec.run(1L, 5L, 202L, List.of(), List.<ToolDefinition>of(), "mock", 3, 15000))
                .isInstanceOf(LlmClientException.class);

        // And exactly one llm_call_log row must have been inserted with success=false.
        ArgumentCaptor<LlmCallLogEntity> captor = ArgumentCaptor.forClass(LlmCallLogEntity.class);
        verify(llmCallLogMapper, times(1)).insert(captor.capture());
        LlmCallLogEntity logged = captor.getValue();
        assertThat(logged.getSuccess()).isFalse();
        assertThat(logged.getErrorCode()).contains("LlmClientException");
    }

    // -----------------------------------------------------------------------
    // FIX 7: dedup hash is key-order-independent (order-normalised serialisation)
    // -----------------------------------------------------------------------

    @Test
    void dedupNormalizesKeyOrder_differentInsertionOrderIsSkipped() {
        LlmClient llmClient = mock(LlmClient.class);
        ToolInvokeService toolInvokeService = mock(ToolInvokeService.class);
        LlmCallLogMapper llmCallLogMapper = mock(LlmCallLogMapper.class);

        // Two LinkedHashMaps with the same key-value pairs but different insertion order.
        LinkedHashMap<String, Object> args1 = new LinkedHashMap<>();
        args1.put("query", "user");
        args1.put("limit", 10);

        LinkedHashMap<String, Object> args2 = new LinkedHashMap<>();
        args2.put("limit", 10);   // reversed insertion order
        args2.put("query", "user");

        ToolCall firstCall  = ToolCall.builder().id("c1").name("searchCodeChunks").arguments(args1).build();
        ToolCall secondCall = ToolCall.builder().id("c2").name("searchCodeChunks").arguments(args2).build();

        when(llmClient.generate(any()))
                .thenReturn(LlmResponse.builder().content("").toolCalls(List.of(firstCall)).build())
                .thenReturn(LlmResponse.builder().content("").toolCalls(List.of(secondCall)).build())
                .thenReturn(LlmResponse.builder().content("done").toolCalls(List.of()).build());
        when(toolInvokeService.invoke(anyLong(), anyLong(), any(), anyString(), anyMap(), any(), any()))
                .thenReturn("result");

        AgentLoopExecutor exec = executor(llmClient, toolInvokeService, llmCallLogMapper);
        AgentLoopExecutor.AgentResult result = exec.run(
                1L, 5L, 202L, List.of(), List.<ToolDefinition>of(), "mock", 5, 15000);

        // Real invoke only ONCE — second call is deduplicated despite different key insertion order.
        verify(toolInvokeService, times(1)).invoke(anyLong(), anyLong(), any(), anyString(), anyMap(), any(), any());
        assertThat(result.getSteps()).hasSize(2);
        assertThat(result.getSteps().get(1).getObservation()).contains("重复调用已跳过");
        assertThat(result.getAnswer()).isEqualTo("done");
        // toolCallCount must NOT count the skipped duplicate.
        assertThat(result.getToolCallCount()).isEqualTo(1);
    }
}
