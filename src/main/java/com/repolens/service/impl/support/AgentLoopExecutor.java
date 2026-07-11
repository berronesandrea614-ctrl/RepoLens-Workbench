package com.repolens.service.impl.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.repolens.common.util.HashUtils;
import com.repolens.domain.entity.LlmCallLogEntity;
import com.repolens.domain.vo.AgentStepVO;
import com.repolens.domain.vo.CodeReferenceVO;
import com.repolens.llm.LlmClient;
import com.repolens.llm.LlmClientException;
import com.repolens.llm.model.LlmMessage;
import com.repolens.llm.model.LlmRequest;
import com.repolens.llm.model.LlmResponse;
import com.repolens.llm.model.ToolCall;
import com.repolens.llm.model.ToolDefinition;
import com.repolens.mapper.AgentRunPlanMapper;
import com.repolens.mapper.LlmCallLogMapper;
import com.repolens.domain.entity.AgentRunPlanEntity;
import com.repolens.domain.enums.PermissionMode;
import com.repolens.service.ToolInvokeService;
import com.repolens.service.support.AgentControlToolHandler;
import com.repolens.service.support.AgentTaskDispatcher;
import com.repolens.service.support.PermissionEvaluator;
import com.repolens.service.support.SteeringQueue;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.repolens.hooks.HookContext;
import com.repolens.hooks.HookDispatcher;
import com.repolens.hooks.HookResult;
import com.repolens.service.support.context.ContextManager;
import com.repolens.service.support.TodoState;
import com.repolens.service.support.WriteApprovalPolicy;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agentic 多步检索循环执行器（ReAct 主循环）。
 *
 * 职责：把"LLM 思考 → 选工具 → 执行 → 观察 → 再决策"这条 loop 跑稳，并把开放的 LLM 决策
 * 关进确定性的工程笼子里：
 * - 工具执行复用 {@link ToolInvokeService}（带 repo 级权限校验 + tool_call_log 审计），
 *   LLM 只能从固定工具集里选只读工具，绝不直接执行写操作；
 * - max-iterations 上限 + 每步超时，防止无限循环烧 token；
 * - 单个工具失败只把错误回填给模型（让它换路），不让整个 loop 崩；
 * - LLM 调用失败抛 {@link LlmClientException} 上抛，由上层统一降级回单轮证据摘要；
 * - 每次真实 LLM 调用后落一条 llm_call_log（失败安全）；
 * - 同一 (tool, argsHash) 在本次 run 内只真正执行一次，重复调用回填固定跳过消息；
 * - 上下文总字符超出预算时，从最旧的 tool 观察开始压缩（system + 最近 2 条观察永不压缩）。
 *
 * 本执行器只产出"最终答案文本 + 轨迹统计"，会话/落库/引用组装仍由 CodeAnswerService 负责，
 * 与单轮链路共用同一套日志与降级逻辑。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentLoopExecutor {

    @Value("${repolens.agent.code-max-iterations:10}")
    private int maxCodeIterations;

    @Value("${repolens.agent.wall-clock-budget-ms:90000}")
    private long wallClockBudgetMs;

    @Value("${repolens.agent.max-tool-turns:8}")
    private int maxToolTurns;

    private static final java.util.concurrent.ExecutorService RO_EXECUTOR =
            java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "agent-ro-");
                t.setDaemon(true);
                return t;
            });

    private static final Set<String> WRITE_OR_EXEC = new java.util.HashSet<>();
    static {
        WRITE_OR_EXEC.addAll(AgentToolCatalog.WRITE_TOOL_NAMES);
        WRITE_OR_EXEC.addAll(AgentToolCatalog.EXEC_TOOL_NAMES);
        WRITE_OR_EXEC.add("TodoWrite");
        WRITE_OR_EXEC.add("Task");
    }

    private static final int MAX_TOOL_RESULT_CHARS = 4000;

    /**
     * Dedicated ObjectMapper for dedup hash computation only.
     * ORDER_MAP_ENTRIES_BY_KEYS makes the serialization key-order-independent so that two
     * semantically identical argument maps with different insertion order produce the same hash.
     */
    private static final ObjectMapper DEDUP_MAPPER;
    static {
        DEDUP_MAPPER = new ObjectMapper();
        DEDUP_MAPPER.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    /** Fixed observation injected when a (tool, args) pair is called a second time in the same run. */
    static final String SKIP_OBSERVATION =
            "(重复调用已跳过——该工具与参数在本次会话中已执行过，请基于已有结果继续)";

    private final LlmClient llmClient;
    private final ToolInvokeService toolInvokeService;
    private final ObjectMapper objectMapper;
    private final LlmCallLogMapper llmCallLogMapper;
    private final PermissionEvaluator permissionEvaluator;
    private final AgentControlToolHandler agentControlToolHandler;
    private final TodoState todoState;
    private final AgentTaskDispatcher agentTaskDispatcher;
    private final AgentRunPlanMapper agentRunPlanMapper;
    private final ContextManager contextManager;
    private final HookDispatcher hookDispatcher;
    private final SteeringQueue steeringQueue;

    /**
     * 运行 agent loop（向后兼容版本，branchId=null）。
     * 所有现有调用方行为逐字节不变。
     *
     * @param seedMessages 初始消息（system + 带证据的 user），由调用方组装
     * @param tools        可用工具定义
     * @param maxIterations 最大轮数（每轮 = 一次 LLM 调用）
     * @param perCallTimeoutMs 单次 LLM 调用超时
     * @return 最终答案与轨迹；LLM 调用失败时抛 LlmClientException 由上层降级
     */
    public AgentResult run(Long userId,
                           Long repoId,
                           Long sessionId,
                           List<LlmMessage> seedMessages,
                           List<ToolDefinition> tools,
                           String modelName,
                           int maxIterations,
                           int perCallTimeoutMs) {
        return run(userId, repoId, sessionId, seedMessages, tools, modelName, maxIterations,
                perCallTimeoutMs, null, null);
    }

    public AgentResult run(Long userId,
                           Long repoId,
                           Long sessionId,
                           List<LlmMessage> seedMessages,
                           List<ToolDefinition> tools,
                           String modelName,
                           int maxIterations,
                           int perCallTimeoutMs,
                           String branchId) {
        return run(userId, repoId, sessionId, seedMessages, tools, modelName, maxIterations,
                perCallTimeoutMs, branchId, null);
    }

    /**
     * 运行 agent loop（K 方案 P1：额外携带 branchId 供写工具落 file_change_log.branch_id）。
     * branchId=null 时与旧签名行为完全相同（向后兼容）。
     *
     * @param branchId 当前分支隔离 id（如 "v1"/"v2"）；null 表示主线，等价现有行为
     */
    public AgentResult run(Long userId,
                           Long repoId,
                           Long sessionId,
                           List<LlmMessage> seedMessages,
                           List<ToolDefinition> tools,
                           String modelName,
                           int maxIterations,
                           int perCallTimeoutMs,
                           String branchId,
                           PermissionMode permissionMode) {
        List<LlmMessage> messages = new ArrayList<>(seedMessages);
        List<AgentStepVO> steps = new ArrayList<>();
        // 用 filePath:startLine 去重累积工具检索到的新证据，保证最终引用可追溯。
        Map<String, CodeReferenceVO> discovered = new LinkedHashMap<>();
        // (toolName + "|" + argsHash) → 已在本次 run 内执行过，重复调用跳过
        Set<String> visited = new HashSet<>();
        int iterations = 0;
        int toolCalls = 0;
        String lastVerifyFingerprint = null;
        int sameVerifyCount = 0;
        String lastContent = "";
        long startMs = System.currentTimeMillis();
        int toolTurns = 0;
        boolean needFinalAnswer = false;

        int safeMaxIters = Math.max(1, maxIterations);
        for (int i = 0; i < safeMaxIters; i++) {
            iterations++;

            // --- Wall clock budget check ---
            long elapsed = System.currentTimeMillis() - startMs;
            if (elapsed > wallClockBudgetMs) {
                messages.add(LlmMessage.builder()
                        .role("system")
                        .content("已超时间预算，请立即给出最终答案。")
                        .build());
                needFinalAnswer = true;
                break;
            }

            // --- Steering: drain user steering messages before LLM call ---
            List<String> steerings = steeringQueue.drain(sessionId);
            if (!steerings.isEmpty()) {
                messages.add(LlmMessage.builder()
                        .role("user")
                        .content("[用户插话] " + String.join(" | ", steerings))
                        .build());
            }

            // --- Context budget guard: compress old observations before sending to LLM ---
            contextManager.compact(messages, 0);

            LlmRequest request = LlmRequest.builder()
                    .modelName(modelName)
                    .temperature(0.2d)
                    .timeoutMs(perCallTimeoutMs)
                    .tools(tools)
                    .messages(messages)
                    .build();

            // LLM 调用失败（超时/配置缺失/HTTP 错误）直接上抛，由 CodeAnswerService 降级为证据摘要。
            // 无论成功还是失败，都落一条 llm_call_log 审计行（失败安全，不让日志破坏主链路）。
            long callStartMs = System.currentTimeMillis();
            LlmResponse response;
            try {
                response = llmClient.generate(request);
            } catch (RuntimeException ex) {
                persistLlmCallLogFailSafe(userId, repoId, sessionId, messages, modelName,
                        System.currentTimeMillis() - callStartMs, ex);
                throw ex;
            }
            // 每次真实 LLM 调用落一条审计日志（失败安全，不影响主链路）。
            // F P1: capture the llm_call_id to thread into write tools below.
            Long currentLlmCallId = persistLlmCallLogSafe(userId, repoId, sessionId, messages, response, modelName,
                    System.currentTimeMillis() - callStartMs);

            lastContent = response.getContent() == null ? "" : response.getContent();

            List<ToolCall> calls = response.getToolCalls();
            if (calls == null || calls.isEmpty()) {
                return buildResult(lastContent, iterations, toolCalls, false, steps, discovered);
            }
            if (response.getFinishReason() != null
                    && !"tool_calls".equals(response.getFinishReason())) {
                return buildResult(lastContent, iterations, toolCalls, false, steps, discovered);
            }

            messages.add(LlmMessage.builder()
                    .role("assistant")
                    .content(lastContent)
                    .toolCalls(calls)
                    .build());

            // Phase 1: execute read-only calls concurrently
            List<ToolCall> roCalls = new ArrayList<>();
            List<ToolCall> rwCalls = new ArrayList<>();
            for (ToolCall call : calls) {
                String visitKey = call.getName() + "|" + HashUtils.sha256(writeDedupArgs(call.getArguments()));
                if (visited.contains(visitKey) || "TodoWrite".equals(call.getName()) || "Task".equals(call.getName()) || "revisePlan".equals(call.getName())) {
                    rwCalls.add(call); // handle inline
                } else if (WRITE_OR_EXEC.contains(call.getName())) {
                    rwCalls.add(call);
                } else {
                    roCalls.add(call);
                }
            }
            // Execute read-only calls in parallel (pre-submit futures; dispatching loop below does the actual visited tracking)
            List<java.util.concurrent.CompletableFuture<ToolExecution>> roFutures = new ArrayList<>();
            for (ToolCall call : roCalls) {
                // Do NOT add to visited here — the dispatch loop below handles dedup uniformly.
                // Double-add would cause both the roCalls pre-processing AND the dispatch loop to mark as visited,
                // leading to zero toolCalls when a read tool is called across iterations.
                roFutures.add(java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> executeToolSafely(userId, repoId, sessionId, call, currentLlmCallId, branchId, permissionMode),
                        RO_EXECUTOR));
            }
            // Execute write/exec calls serially (in order) + collect results
            int roIdx = 0;
            for (ToolCall call : calls) {
                String argsJson = writeArgs(call.getArguments());
                String dedupArgsJson = writeDedupArgs(call.getArguments());
                String visitKey = call.getName() + "|" + HashUtils.sha256(dedupArgsJson);
                if (!visited.add(visitKey)) {
                    log.debug("agent loop: duplicate tool call skipped, tool={}, repoId={}", call.getName(), repoId);
                    steps.add(AgentStepVO.builder().stepIndex(steps.size() + 1).thought(lastContent)
                            .toolName(call.getName()).toolArgs(argsJson).observation(SKIP_OBSERVATION)
                            .discoveredCount(0).build());
                    messages.add(LlmMessage.builder().role("tool").toolCallId(call.getId())
                            .content(SKIP_OBSERVATION).build());
                    continue;
                }
                if ("TodoWrite".equals(call.getName())) {
                    String obs = agentControlToolHandler.handleTodoWrite(sessionId, call.getArguments());
                    messages.add(LlmMessage.builder().role("tool").toolCallId(call.getId()).content(obs).build());
                    continue;
                }
                if ("Task".equals(call.getName())) {
                    String obs = agentTaskDispatcher.dispatchTask(userId, repoId, sessionId, sessionId, call.getArguments());
                    messages.add(LlmMessage.builder().role("tool").toolCallId(call.getId()).content(obs).build());
                    continue;
                }
                if ("revisePlan".equals(call.getName())) {
                    String obs = handleRevisePlan(sessionId, call.getArguments());
                    messages.add(LlmMessage.builder().role("tool").toolCallId(call.getId()).content(obs).build());
                    continue;
                }
                toolCalls++;
                ToolExecution exec;
                if (!WRITE_OR_EXEC.contains(call.getName()) && roIdx < roFutures.size()) {
                    try { exec = roFutures.get(roIdx++).get(); }
                    catch (Exception e) { exec = new ToolExecution(buildErrorJson(e.getMessage()), List.of()); }
                } else {
                    exec = executeToolSafely(userId, repoId, sessionId, call, currentLlmCallId, branchId, permissionMode);
                }
                if ("runVerification".equals(call.getName())) {
                    String fp = HashUtils.sha256(exec.observation());
                    if (fp.equals(lastVerifyFingerprint)) {
                        sameVerifyCount++;
                        if (sameVerifyCount >= 2) {
                            log.info("agent early-stop: runVerification failure unchanged for 2 rounds, repoId={}", repoId);
                            messages.add(LlmMessage.builder().role("tool").toolCallId(call.getId())
                                    .content("[系统] 验证失败内容连续2轮未变，当前修改无法解决问题，请换一种方案或停止。").build());
                            return buildResult("", iterations, toolCalls, true, steps, discovered);
                        }
                    } else { lastVerifyFingerprint = fp; sameVerifyCount = 0; }
                }
                for (CodeReferenceVO ref : exec.references()) { discovered.putIfAbsent(refKey(ref), ref); }
                steps.add(AgentStepVO.builder().stepIndex(steps.size() + 1).thought(lastContent)
                        .toolName(call.getName()).toolArgs(argsJson).observation(exec.observation())
                        .discoveredCount(exec.references().size()).build());
                messages.add(LlmMessage.builder().role("tool").toolCallId(call.getId()).content(exec.observation()).build());
            }

            toolTurns++;
            if (toolTurns >= maxToolTurns) {
                messages.add(LlmMessage.builder()
                        .role("system")
                        .content("已达工具调用上限，请基于现有信息给出最终答案，不要再调用工具。")
                        .build());
                needFinalAnswer = true;
                break;
            }
        }

        if (needFinalAnswer) {
            try {
                contextManager.compact(messages, 0);
                LlmRequest finalRequest = LlmRequest.builder()
                        .modelName(modelName)
                        .temperature(0.2d)
                        .timeoutMs(perCallTimeoutMs)
                        .tools(List.of())
                        .messages(messages)
                        .build();
                LlmResponse finalResponse = llmClient.generate(finalRequest);
                persistLlmCallLogSafe(userId, repoId, sessionId, messages, finalResponse, modelName,
                        System.currentTimeMillis() - startMs);
                lastContent = finalResponse.getContent() == null ? "" : finalResponse.getContent();
                return buildResult(lastContent, iterations, toolCalls, true, steps, discovered);
            } catch (Exception e) {
                log.warn("Final answer LLM call after budget/turn limit failed: {}", e.getMessage());
            }
        }
        return buildResult("", iterations, toolCalls, true, steps, discovered);
    }

    /**
     * 真流式 agent loop（向后兼容版本，branchId=null）。
     * 所有现有调用方行为逐字节不变。
     *
     * <p>审计约束（同 {@link #run}）：每次真实 LLM 调用落 llm_call_log；(tool,args) dedup 跳过；
     * 上下文预算压缩。
     *
     * @param streamListener 流式事件回调（step / answerToken）；调用方负责转发 SSE
     * @return 最终结果（同 run）；LLM 调用失败时抛 LlmClientException 由上层降级
     */
    public AgentResult runStreaming(Long userId,
                                    Long repoId,
                                    Long sessionId,
                                    List<LlmMessage> seedMessages,
                                    List<ToolDefinition> tools,
                                    String modelName,
                                    int maxIterations,
                                    int perCallTimeoutMs,
                                    AgentStreamListener streamListener) {
        return runStreaming(userId, repoId, sessionId, seedMessages, tools, modelName, maxIterations,
                perCallTimeoutMs, streamListener, null, null);
    }

    public AgentResult runStreaming(Long userId,
                                     Long repoId,
                                     Long sessionId,
                                     List<LlmMessage> seedMessages,
                                     List<ToolDefinition> tools,
                                     String modelName,
                                     int maxIterations,
                                     int perCallTimeoutMs,
                                     AgentStreamListener streamListener,
                                     String branchId) {
        return runStreaming(userId, repoId, sessionId, seedMessages, tools, modelName, maxIterations,
                perCallTimeoutMs, streamListener, branchId, null);
    }

    /**
     * 真流式 agent loop（K 方案 P1：额外携带 branchId）。
     * branchId=null 时与旧签名行为完全相同（向后兼容）。
     *
     * @param branchId 当前分支隔离 id（如 "v1"/"v2"）；null 表示主线，等价现有行为
     */
    public AgentResult runStreaming(Long userId,
                                     Long repoId,
                                     Long sessionId,
                                     List<LlmMessage> seedMessages,
                                     List<ToolDefinition> tools,
                                     String modelName,
                                     int maxIterations,
                                     int perCallTimeoutMs,
                                     AgentStreamListener streamListener,
                                     String branchId,
                                     PermissionMode permissionMode) {
        List<LlmMessage> messages = new ArrayList<>(seedMessages);
        List<AgentStepVO> steps = new ArrayList<>();
        Map<String, CodeReferenceVO> discovered = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>();
        int iterations = 0;
        int toolCalls = 0;
        String lastVerifyFingerprint = null;
        int sameVerifyCount = 0;
        String lastContent = "";
        long startMs = System.currentTimeMillis();
        int toolTurns = 0;
        boolean needFinalAnswer = false;

        int safeMaxIters = Math.max(1, maxIterations);
        for (int i = 0; i < safeMaxIters; i++) {
            iterations++;

            long elapsed = System.currentTimeMillis() - startMs;
            if (elapsed > wallClockBudgetMs) {
                messages.add(LlmMessage.builder()
                        .role("system")
                        .content("已超时间预算，请立即给出最终答案。")
                        .build());
                needFinalAnswer = true;
                break;
            }

            List<String> steerings = steeringQueue.drain(sessionId);
            if (!steerings.isEmpty()) {
                messages.add(LlmMessage.builder()
                        .role("user")
                        .content("[用户插话] " + String.join(" | ", steerings))
                        .build());
            }

            contextManager.compact(messages, 0);

            LlmRequest request = LlmRequest.builder()
                    .modelName(modelName)
                    .temperature(0.2d)
                    .timeoutMs(perCallTimeoutMs)
                    .tools(tools)
                    .messages(messages)
                    .build();

            long callStartMs = System.currentTimeMillis();

            // 每轮缓冲 content tokens：确认本轮无 tool_calls 后才 flush 为答案 token。
            List<String> roundTokenBuffer = new ArrayList<>();
            // 使用数组捕获 lambda 中的可变引用。
            LlmResponse[] doneHolder = new LlmResponse[1];
            RuntimeException[] errorHolder = new RuntimeException[1];

            try {
                llmClient.generateStreamWithTools(request, new com.repolens.llm.StreamWithToolsListener() {
                    @Override
                    public void onContentToken(String token) {
                        roundTokenBuffer.add(token);
                    }

                    @Override
                    public void onToolCallStart(String toolName) {
                        // 工具调用开始：工具名已知，但实际执行 + 观察在 onDone 后处理。
                        // 不在此发 step 事件（等工具执行完再发完整 step）。
                    }

                    @Override
                    public void onDone(LlmResponse response) {
                        doneHolder[0] = response;
                        // 仅当本轮无 tool_calls（最终答案轮）才 flush token 缓冲。
                        boolean isAnswerRound = response == null
                                || response.getToolCalls() == null
                                || response.getToolCalls().isEmpty();
                        if (isAnswerRound) {
                            for (String token : roundTokenBuffer) {
                                streamListener.onAnswerToken(token);
                            }
                        }
                        // 工具轮：内容是中间思考文本，静默丢弃，不作为答案 token 下发。
                    }
                });
            } catch (RuntimeException ex) {
                persistLlmCallLogFailSafe(userId, repoId, sessionId, messages, modelName,
                        System.currentTimeMillis() - callStartMs, ex);
                throw ex;
            }

            LlmResponse response = doneHolder[0];
            if (response == null) {
                LlmClientException ex = new LlmClientException("LLM_CALL_FAILED",
                        "generateStreamWithTools returned null response");
                persistLlmCallLogFailSafe(userId, repoId, sessionId, messages, modelName,
                        System.currentTimeMillis() - callStartMs, ex);
                throw ex;
            }

            Long currentLlmCallIdStreaming = persistLlmCallLogSafe(userId, repoId, sessionId, messages, response, modelName,
                    System.currentTimeMillis() - callStartMs);

            lastContent = response.getContent() == null ? "" : response.getContent();

            List<ToolCall> calls = response.getToolCalls();
            if (calls == null || calls.isEmpty()) {
                return buildResult(lastContent, iterations, toolCalls, false, steps, discovered);
            }
            if (response.getFinishReason() != null
                    && !"tool_calls".equals(response.getFinishReason())) {
                return buildResult(lastContent, iterations, toolCalls, false, steps, discovered);
            }

            messages.add(LlmMessage.builder()
                    .role("assistant")
                    .content(lastContent)
                    .toolCalls(calls)
                    .build());

            for (ToolCall call : calls) {
                String argsJson = writeArgs(call.getArguments());
                String dedupArgsJson = writeDedupArgs(call.getArguments());
                String visitKey = call.getName() + "|" + HashUtils.sha256(dedupArgsJson);

                if (!visited.add(visitKey)) {
                    log.debug("agent streaming loop: duplicate tool call skipped, tool={}, repoId={}",
                            call.getName(), repoId);
                    AgentStepVO stepVO = AgentStepVO.builder()
                            .stepIndex(steps.size() + 1)
                            .thought(lastContent)
                            .toolName(call.getName())
                            .toolArgs(argsJson)
                            .observation(SKIP_OBSERVATION)
                            .discoveredCount(0)
                            .build();
                    steps.add(stepVO);
                    streamListener.onStep(stepVO);
                    messages.add(LlmMessage.builder()
                            .role("tool")
                            .toolCallId(call.getId())
                            .content(SKIP_OBSERVATION)
                            .build());
                } else {
                    if ("TodoWrite".equals(call.getName())) {
                        String observation = agentControlToolHandler.handleTodoWrite(sessionId, call.getArguments());
                        messages.add(LlmMessage.builder()
                                .role("tool")
                                .toolCallId(call.getId())
                                .content(observation)
                                .build());
                        continue;
                    }
                    if ("Task".equals(call.getName())) {
                        String observation = agentTaskDispatcher.dispatchTask(
                                userId, repoId, sessionId, sessionId, call.getArguments());
                        messages.add(LlmMessage.builder()
                                .role("tool")
                                .toolCallId(call.getId())
                                .content(observation)
                                .build());
                        continue;
                    }
                    if ("revisePlan".equals(call.getName())) {
                        String observation = handleRevisePlan(sessionId, call.getArguments());
                        messages.add(LlmMessage.builder()
                                .role("tool")
                                .toolCallId(call.getId())
                                .content(observation)
                                .build());
                        continue;
                    }
                    toolCalls++;
                    ToolExecution exec = executeToolSafely(userId, repoId, sessionId, call, currentLlmCallIdStreaming, branchId, permissionMode);
                    if ("runVerification".equals(call.getName())) {
                        String fp = HashUtils.sha256(exec.observation());
                        if (fp.equals(lastVerifyFingerprint)) {
                            sameVerifyCount++;
                            if (sameVerifyCount >= 2) {
                                log.info("agent early-stop: runVerification failure unchanged for 2 rounds, repoId={}", repoId);
                                messages.add(LlmMessage.builder()
                                        .role("tool")
                                        .toolCallId(call.getId())
                                        .content("[系统] 验证失败内容连续2轮未变，当前修改无法解决问题，请换一种方案或停止。")
                                        .build());
                                return buildResult("", iterations, toolCalls, true, steps, discovered);
                            }
                        } else {
                            lastVerifyFingerprint = fp;
                            sameVerifyCount = 0;
                        }
                    }
                    for (CodeReferenceVO ref : exec.references()) {
                        discovered.putIfAbsent(refKey(ref), ref);
                    }
                    AgentStepVO stepVO = AgentStepVO.builder()
                            .stepIndex(steps.size() + 1)
                            .thought(lastContent)
                            .toolName(call.getName())
                            .toolArgs(argsJson)
                            .observation(exec.observation())
                            .discoveredCount(exec.references().size())
                            .build();
                    steps.add(stepVO);
                    streamListener.onStep(stepVO);
                    messages.add(LlmMessage.builder()
                            .role("tool")
                            .toolCallId(call.getId())
                            .content(exec.observation())
                            .build());
                }
            }

            toolTurns++;
            if (toolTurns >= maxToolTurns) {
                messages.add(LlmMessage.builder()
                        .role("system")
                        .content("已达工具调用上限，请基于现有信息给出最终答案，不要再调用工具。")
                        .build());
                needFinalAnswer = true;
                break;
            }
        }

        if (needFinalAnswer) {
            try {
                contextManager.compact(messages, 0);
                LlmRequest finalRequest = LlmRequest.builder()
                        .modelName(modelName)
                        .temperature(0.2d)
                        .timeoutMs(perCallTimeoutMs)
                        .tools(List.of())
                        .messages(messages)
                        .build();
                LlmResponse finalResponse = llmClient.generate(finalRequest);
                persistLlmCallLogSafe(userId, repoId, sessionId, messages, finalResponse, modelName,
                        System.currentTimeMillis() - startMs);
                lastContent = finalResponse.getContent() == null ? "" : finalResponse.getContent();
                return buildResult(lastContent, iterations, toolCalls, true, steps, discovered);
            } catch (Exception e) {
                log.warn("Final answer LLM call after budget/turn limit failed: {}", e.getMessage());
            }
        }

        return buildResult("", iterations, toolCalls, true, steps, discovered);
    }

    /**
     * 每次真实 LLM 调用后落一条 llm_call_log，失败安全：任何异常静默吞掉，不影响 loop 主链路。
     * Feature F P1: 返回插入行的 id（null on failure），供写工具写入 file_change_log.llm_call_id。
     */
    private Long persistLlmCallLogSafe(Long userId, Long repoId, Long sessionId,
                                        List<LlmMessage> messages, LlmResponse response,
                                        String modelName, long measuredCostMs) {
        try {
            // Compute prompt hash from concatenated message contents (uniquely represents this call context).
            StringBuilder promptBuf = new StringBuilder();
            StringBuilder contextBuf = new StringBuilder(); // F P1: tool-role messages = retrieval context
            for (LlmMessage m : messages) {
                if (m.getContent() != null) {
                    promptBuf.append(m.getContent()).append("\n");
                    if ("tool".equals(m.getRole())) {
                        contextBuf.append(m.getContent()).append("\n");
                    }
                }
            }
            String promptText = promptBuf.toString();
            String responseText = response != null && response.getContent() != null
                    ? response.getContent() : "";

            String effectiveModelName = response != null && StringUtils.hasText(response.getModelName())
                    ? response.getModelName() : modelName;

            LlmCallLogEntity entity = new LlmCallLogEntity();
            entity.setUserId(userId);
            entity.setRepoId(repoId);
            entity.setSessionId(sessionId);
            entity.setModelName(effectiveModelName);
            entity.setPromptHash(HashUtils.sha256(promptText));
            entity.setResponseHash(HashUtils.sha256(responseText));
            entity.setTokenInput(
                    response != null && response.getPromptTokens() != null && response.getPromptTokens() > 0
                    ? response.getPromptTokens() : Math.max(1, promptText.length() / 4));
            entity.setTokenOutput(
                    response != null && response.getCompletionTokens() != null && response.getCompletionTokens() > 0
                    ? response.getCompletionTokens() : Math.max(1, responseText.length() / 4));
            entity.setCostMs(
                    response != null && response.getCostMs() != null && response.getCostMs() >= 0
                    ? response.getCostMs() : measuredCostMs);
            entity.setSuccess(true);
            entity.setErrorCode(null);
            // F P1: populate trace columns
            entity.setProvider(inferProvider(effectiveModelName));
            entity.setModelVersion(effectiveModelName);
            entity.setContextHash(HashUtils.sha256(contextBuf.toString()));
            llmCallLogMapper.insert(entity);
            return entity.getId(); // auto-generated by MyBatis-Plus after insert
        } catch (Exception ex) {
            log.warn("agent loop: persist llm_call_log failed, ignore, repoId={}, err={}", repoId, ex.getMessage());
            return null;
        }
    }

    /** F P1: Infer provider from model name (best-effort, no external call). */
    private static String inferProvider(String modelName) {
        if (modelName == null) return null;
        String lower = modelName.toLowerCase();
        if (lower.startsWith("deepseek")) return "deepseek";
        if (lower.startsWith("gpt") || lower.startsWith("o1") || lower.startsWith("o3")) return "openai";
        if (lower.startsWith("claude")) return "anthropic";
        if (lower.startsWith("gemini")) return "google";
        if (lower.startsWith("llama") || lower.startsWith("qwen") || lower.startsWith("mistral")
                || lower.startsWith("phi") || lower.startsWith("gemma")) return "ollama";
        return "unknown";
    }

    /**
     * LLM 调用抛出异常时落一条 success=false 审计行（失败安全：任何异常静默吞掉，不影响主链路）。
     * errorCode 字段存储 "ExceptionClassName: message"，截断到 200 字符。
     * Feature F P1: always returns null (failed calls don't contribute a traceable llm_call_id).
     */
    private Long persistLlmCallLogFailSafe(Long userId, Long repoId, Long sessionId,
                                            List<LlmMessage> messages, String modelName,
                                            long measuredCostMs, Exception cause) {
        try {
            StringBuilder promptBuf = new StringBuilder();
            for (LlmMessage m : messages) {
                if (m.getContent() != null) {
                    promptBuf.append(m.getContent()).append("\n");
                }
            }
            String promptText = promptBuf.toString();
            String causeMsg = cause.getMessage() != null ? cause.getMessage() : "no detail";
            String errorSummary = cause.getClass().getSimpleName() + ": " + causeMsg;
            if (errorSummary.length() > 200) {
                errorSummary = errorSummary.substring(0, 200);
            }

            LlmCallLogEntity entity = new LlmCallLogEntity();
            entity.setUserId(userId);
            entity.setRepoId(repoId);
            entity.setSessionId(sessionId);
            entity.setModelName(modelName);
            entity.setPromptHash(HashUtils.sha256(promptText));
            entity.setResponseHash(HashUtils.sha256(""));
            entity.setTokenInput(Math.max(1, promptText.length() / 4));
            entity.setTokenOutput(0);
            entity.setCostMs(measuredCostMs);
            entity.setSuccess(false);
            entity.setErrorCode(errorSummary);
            entity.setProvider(inferProvider(modelName));
            entity.setModelVersion(modelName);
            llmCallLogMapper.insert(entity);
        } catch (Exception ex) {
            log.warn("agent loop: persist failed llm_call_log failed, ignore, repoId={}, err={}", repoId, ex.getMessage());
        }
        return null;
    }

    private AgentResult buildResult(String answer, int iterations, int toolCalls, boolean hitMax,
                                    List<AgentStepVO> steps, Map<String, CodeReferenceVO> discovered) {
        return AgentResult.builder()
                .answer(answer)
                .iterations(iterations)
                .toolCallCount(toolCalls)
                .hitMaxIterations(hitMax)
                .steps(steps)
                .discoveredReferences(new ArrayList<>(discovered.values()))
                .build();
    }

    /**
     * 执行单个工具调用，失败安全：任何异常都转成给模型的错误文本，不让 loop 崩。
     * 权限校验与审计在 ToolInvokeService 内部完成。
     * 同时从结果里提取可追溯的代码引用（凡含 filePath 的结果项）。
     * Feature F P1: llmCallId 传入写工具以填写 file_change_log.llm_call_id。
     * K P1: branchId 透传到写工具以落 file_change_log.branch_id；null = 主线，行为不变。
     */
    private ToolExecution executeToolSafely(Long userId, Long repoId, Long sessionId, ToolCall call,
                                            Long llmCallId, String branchId, PermissionMode permissionMode) {
        try {
            String toolName = call.getName();
            Map<String, Object> toolArgs = call.getArguments();

            HookContext hookCtx = HookContext.builder()
                    .lifecycle("PreToolUse")
                    .toolName(toolName)
                    .toolArgs(toolArgs)
                    .userId(userId)
                    .repoId(repoId)
                    .sessionId(sessionId)
                    .build();
            HookResult preResult = hookDispatcher.dispatchPreToolUse(hookCtx);
            if (!preResult.isContinueFlow()) {
                return new ToolExecution(buildErrorJson("hook blocked: " + preResult.getReason()), List.of());
            }

            if (permissionMode != null && PermissionEvaluator.WRITE_TOOLS.contains(toolName)) {
                String targetPath = extractTargetPath(toolArgs);
                PermissionEvaluator.Verdict verdict = permissionEvaluator.evaluate(permissionMode, toolName, targetPath);
                if (verdict.decision() == WriteApprovalPolicy.Decision.BLOCK) {
                    return new ToolExecution(agentToolExecutionErrorJson("工具 " + toolName + " 被权限策略拒绝："
                            + (verdict.reason() != null ? verdict.reason() : "当前模式不允许此操作")), List.of());
                }
            }
            Object result = toolInvokeService.invoke(userId, repoId, sessionId, call.getName(), call.getArguments(),
                    llmCallId, branchId);
            JsonNode resultNode = objectMapper.valueToTree(result);
            List<CodeReferenceVO> refs = extractReferences(resultNode);
            String json = objectMapper.writeValueAsString(result);
            if (json.length() > MAX_TOOL_RESULT_CHARS) {
                json = json.substring(0, MAX_TOOL_RESULT_CHARS) + "...(truncated)";
            }
            return new ToolExecution(json, refs);
        } catch (Exception ex) {
            log.warn("agent tool execution failed, tool={}, repoId={}", call.getName(), repoId);
            return new ToolExecution(buildErrorJson(ex.getMessage()), List.of());
        }
    }

    /**
     * 用 ObjectMapper 生成错误 JSON，保证 Windows 路径 C:\foo、正则 \f 等含反斜杠/引号的内容
     * 被正确转义，不再产出非法 JSON（旧实现手工拼接仅把 " 换成 '，不转义 \\）。
     */
    private String buildErrorJson(String rawMessage) {
        try {
            return objectMapper.writeValueAsString(
                    Map.of("error", "tool execution failed: " + trimError(rawMessage)));
        } catch (Exception ex) {
            return "{\"error\":\"tool execution failed\"}";
        }
    }

    /**
     * 从工具结果 JSON 中通用提取代码引用：凡是含 filePath 字段的对象（数组逐项处理）
     * 都抽成 CodeReferenceVO。覆盖 searchCodeChunks/findSymbolByName/findApiByPath/
     * getFileContent/findMethodCallers 等返回，无需按类型硬编码。
     */
    private List<CodeReferenceVO> extractReferences(JsonNode node) {
        List<CodeReferenceVO> refs = new ArrayList<>();
        if (node == null || node.isNull()) {
            return refs;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                refs.addAll(extractReferences(item));
            }
            return refs;
        }
        if (node.isObject() && node.hasNonNull("filePath")) {
            refs.add(CodeReferenceVO.builder()
                    .filePath(node.path("filePath").asText())
                    .chunkType(node.path("chunkType").asText(null))
                    .className(node.path("className").asText(null))
                    .methodName(node.path("methodName").asText(null))
                    .startLine(node.path("startLine").isInt() ? node.path("startLine").asInt() : null)
                    .endLine(node.path("endLine").isInt() ? node.path("endLine").asInt() : null)
                    .score(node.path("score").isNumber() ? (float) node.path("score").asDouble() : null)
                    .contentPreview(node.path("contentPreview").asText(null))
                    .build());
        }
        return refs;
    }

    private String refKey(CodeReferenceVO ref) {
        return ref.getFilePath() + ":" + ref.getStartLine();
    }

    private String writeArgs(Map<String, Object> args) {
        try {
            return objectMapper.writeValueAsString(args == null ? Map.of() : args);
        } catch (Exception ex) {
            return "{}";
        }
    }

    /**
     * Serialise args using {@link #DEDUP_MAPPER} (keys sorted alphabetically) so that two maps
     * with identical entries but different insertion order produce the same JSON string and
     * therefore the same SHA-256 dedup key.  Used only for the dedup hash, not for display.
     */
    private String writeDedupArgs(Map<String, Object> args) {
        try {
            return DEDUP_MAPPER.writeValueAsString(args == null ? Map.of() : args);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private String extractTargetPath(Map<String, Object> args) {
        if (args == null) return null;
        Object fp = args.get("filePath");
        return fp instanceof String s ? s : null;
    }

    /** revisePlan 工具处理：覆盖式重写计划，版本号 +1。 */
    private String handleRevisePlan(Long runId, Map<String, Object> args) {
        try {
            String reason = (String) args.getOrDefault("reason", "");
            var plan = agentRunPlanMapper.selectOne(
                    new QueryWrapper<AgentRunPlanEntity>().eq("agent_run_id", runId));
            if (plan != null) {
                plan.setPlanJson(objectMapper.writeValueAsString(args.get("newSteps")));
                plan.setPlanVersion(plan.getPlanVersion() == null ? 2 : plan.getPlanVersion() + 1);
                agentRunPlanMapper.updateById(plan);
                return "计划已更新（版本 " + plan.getPlanVersion() + "）。原因：" + reason;
            }
            return "revisePlan: 未找到当前计划。";
        } catch (Exception e) {
            log.warn("revisePlan: update plan failed (fail-safe): {}", e.getMessage());
            return "revisePlan error: " + e.getMessage();
        }
    }

    private String agentToolExecutionErrorJson(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of("error", message));
        } catch (Exception e) {
            return "{\"error\":\"" + message.replace("\"", "'") + "\"}";
        }
    }

    /** 单次工具执行结果：给模型看的观察文本 + 提取出的可追溯引用。 */
    private record ToolExecution(String observation, List<CodeReferenceVO> references) {
    }

    private String trimError(String error) {
        if (!StringUtils.hasText(error)) {
            return "unknown";
        }
        String trimmed = error.trim().replaceAll("\\s+", " ").replace("\"", "'");
        return trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
    }

    /**
     * 真流式 agent 循环监听器，供 CodeAnswerServiceImpl 接收增量事件。
     * 每次工具执行完成回调 onStep，最终答案轮的每个 token 回调 onAnswerToken。
     */
    public interface AgentStreamListener {
        /** 一步工具调用完成（思考 + 工具 + 观察），供前端增量渲染轨迹。 */
        void onStep(AgentStepVO step);
        /** 最终答案轮的一个 token 增量（确认本轮无 tool_calls 后 flush）。 */
        void onAnswerToken(String token);
    }

    @Getter
    @Builder
    public static class AgentResult {
        private final String answer;
        private final int iterations;
        private final int toolCallCount;
        private final boolean hitMaxIterations;
        /** 执行轨迹（思考-工具-观察），供前端可视化。 */
        private final List<AgentStepVO> steps;
        /** 工具多步检索发现的新代码引用（已去重），并入最终引用以保证可追溯。 */
        private final List<CodeReferenceVO> discoveredReferences;
    }
}
