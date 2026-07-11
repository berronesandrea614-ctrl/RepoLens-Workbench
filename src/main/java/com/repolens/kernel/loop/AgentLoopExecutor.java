package com.repolens.kernel.loop;

import com.repolens.kernel.context.ContextManager;
import com.repolens.kernel.hook.HookDispatcher;
import com.repolens.kernel.realtime.RealtimeChangeEmitter;
import com.repolens.kernel.spi.ToolContext;
import com.repolens.kernel.steering.SteeringQueue;
import com.repolens.kernel.todo.TodoWriteTool;
import com.repolens.llm.LlmClient;
import com.repolens.llm.model.LlmMessage;
import com.repolens.llm.model.LlmRequest;
import com.repolens.llm.model.LlmResponse;
import com.repolens.llm.model.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 极简 agent 主循环（规划 §2 目标架构）。取代旧版 1138 行 god class，本体只做<b>编排</b>，
 * 所有能力委托专门协作者：LLM 调用→{@link LlmClient}，工具调度→{@link ToolTurnScheduler}，
 * 工具目录→{@link ToolRouter}，预算→{@link AgentBudget}/{@link Tokenizer}。
 *
 * <p>循环骨架：组装消息 → LLM 调用 → <b>无 tool_call 即止</b>（stop_reason≠tool_use，非迭代计数）
 * → 只读并发/写串行调度 → 观察回填 → 预算检查 → 下一轮。
 * compaction/steering/todo/hooks 是后续里程碑（M5–M7）的委托点，此处留白不耦合。
 *
 * <p>终止只认三种真实信号：LLM 不再要工具（自然完成）、预算耗尽、LLM 报错。
 * 另设一个高位 {@code hardTurnCap} 仅作失控 loop 的兜底护栏，不是正常终止判据。
 */
@Component("kernelAgentLoopExecutor")
public class AgentLoopExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentLoopExecutor.class);

    /** 失控兜底：正常任务远达不到，仅防 LLM 死循环。 */
    private static final int HARD_TURN_CAP = 50;

    /**
     * 收尾前的对抗性自检提示：改过文件的 run 在 agent 想结束时注入一次，逼它扮演"想弄垮代码的评审"、
     * 用最可能暴露 bug 的输入真运行核对输出。靠<b>执行</b>照出 bug（如 10/4=2.0≠2.5），绕过弱模型的推理盲点。
     */
    private static final String ADVERSARIAL_REVIEW = """
            [收尾前的对抗性自检 · 必做] 在宣布完成之前，先扮演一个想弄垮你代码的评审：针对你刚写/改的代码，
            主动构造最可能暴露 bug 的输入并<b>真正运行</b>核对输出，尤其是：
            ① 非整除（如 10/4 应为 2.5、7/2 应为 3.5——别让整数除法把小数丢了）；② 负数；③ 零/空输入；
            ④ 边界值、越界；⑤ 非法输入是否按约定抛异常。
            用 bash 真跑一遍（java -cp / mvn -o exec:java）把这些用例的实际输出打出来，逐一对照正确答案。
            只要发现任何一个输出不符合预期，就回去修复、重新运行核对；全部无误后，才用一小段话简洁收尾。
            """;

    private final LlmClient llmClient;
    private final ToolRouter router;
    private final ToolTurnScheduler scheduler;
    private final Tokenizer tokenizer;
    private final TodoWriteTool todoWriteTool;
    private final ContextManager contextManager;
    private final HookDispatcher hookDispatcher;
    private final SteeringQueue steeringQueue;
    private final RealtimeChangeEmitter realtimeChangeEmitter;

    /** 收尾前对抗性自检开关（默认开）：改过文件的 run 在 agent 想结束时强制自检一次再结束。 */
    @Value("${repolens.kernel.adversarial-review:true}")
    private boolean adversarialReview;

    public AgentLoopExecutor(LlmClient llmClient, ToolRouter router,
                             ToolTurnScheduler scheduler, Tokenizer tokenizer,
                             TodoWriteTool todoWriteTool, ContextManager contextManager,
                             HookDispatcher hookDispatcher, SteeringQueue steeringQueue,
                             RealtimeChangeEmitter realtimeChangeEmitter) {
        this.llmClient = llmClient;
        this.router = router;
        this.scheduler = scheduler;
        this.tokenizer = tokenizer;
        this.todoWriteTool = todoWriteTool;
        this.contextManager = contextManager;
        this.hookDispatcher = hookDispatcher;
        this.steeringQueue = steeringQueue;
        this.realtimeChangeEmitter = realtimeChangeEmitter;
    }

    /**
     * 一次 agent run 的输入。
     *
     * @param systemPrompt 系统提示词（M4 会重写，这里只做透传）
     * @param userPrompt   用户任务
     * @param modelName    目标模型名
     * @param ctx          工具执行上下文（影子区/读记账本等）
     * @param maxTokens    token 预算（≤0 不限）
     * @param wallClockMs  墙钟预算毫秒（≤0 不限）
     * @param contextWindowTokens 模型上下文窗口 token 数（M6 compaction 触发依据；≤0 不做上下文压缩）
     * @param realtimeDiff 实时改动流开关：开启则每轮把新落影子区的文件改动实时 emit 给 {@link RunListener#onFileChange}
     */
    public record RunSpec(String systemPrompt, String userPrompt, String modelName,
                          ToolContext ctx, long maxTokens, long wallClockMs,
                          int contextWindowTokens, boolean realtimeDiff, List<LlmMessage> priorMessages) {

        /** 规范构造：priorMessages 为空时归一到空列表（多轮对话历史，seed 进 messages 让 agent 记得上文）。 */
        public RunSpec {
            if (priorMessages == null) {
                priorMessages = List.of();
            }
        }

        /** 兼容：带实时开关、不带历史对话。 */
        public RunSpec(String systemPrompt, String userPrompt, String modelName,
                       ToolContext ctx, long maxTokens, long wallClockMs, int contextWindowTokens, boolean realtimeDiff) {
            this(systemPrompt, userPrompt, modelName, ctx, maxTokens, wallClockMs, contextWindowTokens, realtimeDiff, List.of());
        }

        /** 兼容：带上下文窗口、不带实时开关（realtimeDiff=false）。 */
        public RunSpec(String systemPrompt, String userPrompt, String modelName,
                       ToolContext ctx, long maxTokens, long wallClockMs, int contextWindowTokens) {
            this(systemPrompt, userPrompt, modelName, ctx, maxTokens, wallClockMs, contextWindowTokens, false);
        }

        /** 兼容旧调用点（不带上下文窗口/实时）：contextWindowTokens=0 → 不触发五层压缩（仅 L0 落磁盘）。 */
        public RunSpec(String systemPrompt, String userPrompt, String modelName,
                       ToolContext ctx, long maxTokens, long wallClockMs) {
            this(systemPrompt, userPrompt, modelName, ctx, maxTokens, wallClockMs, 0, false);
        }
    }

    public AgentRunResult run(RunSpec spec) {
        return run(spec, RunListener.NOOP);
    }

    /**
     * 带过程监听的执行：每完成一次工具调用回调 {@link RunListener#onToolStep}，
     * 产出最终答案回调 {@link RunListener#onFinalText}——供 app bridge 转 SSE/可视化。
     * 与 {@link #run(RunSpec)} 语义完全一致，只多了回调，不改变终止/预算/调度逻辑。
     */
    public AgentRunResult run(RunSpec spec, RunListener listener) {
        RunListener lsn = listener == null ? RunListener.NOOP : listener;
        List<LlmMessage> messages = new ArrayList<>();
        // 先塞入本会话的历史对话（多轮记忆）——让 agent 记得上文，"做完了吗"这类追问才有依据。
        if (spec.priorMessages() != null && !spec.priorMessages().isEmpty()) {
            messages.addAll(spec.priorMessages());
        }
        // M7.1 UserPromptSubmit Hook：用户输入进 loop 前的确定性校验/增强（链式改写）。
        String userPrompt = hookDispatcher.runUserPromptSubmit(spec.userPrompt(), spec.ctx());
        messages.add(userMessage(userPrompt));

        AgentBudget budget = AgentBudget.starting(spec.maxTokens(), spec.wallClockMs());
        ContextManager.State ctxState = new ContextManager.State();
        int turns = 0;
        int toolCallCount = 0;
        int stepIndex = 0;
        // 对抗性自检门用：本 run 是否改过文件、是否已自检过（只触发一次防死循环）。
        boolean madeChanges = false;
        boolean adversarialReviewed = false;
        // 实时改动流基线：只推 run 开始后新落的改动（避免把历史改动误当本 run 的新改动）。
        long realtimeSinceId = spec.realtimeDiff() && spec.ctx() != null
                ? realtimeChangeEmitter.baseline(spec.ctx().runId()) : 0L;

        while (true) {
            // 轮首预算闸
            String exhausted = budget.exhaustedReason(System.nanoTime());
            if (exhausted != null) {
                log.info("[loop] 终止：{}", exhausted);
                return result(null, turns, toolCallCount,
                        AgentRunResult.TerminationReason.BUDGET_EXHAUSTED, budget, messages);
            }
            if (turns >= HARD_TURN_CAP) {
                log.warn("[loop] 触发失控兜底护栏 {} 轮，强制终止", HARD_TURN_CAP);
                return result(null, turns, toolCallCount,
                        AgentRunResult.TerminationReason.BUDGET_EXHAUSTED, budget, messages);
            }

            // M7.2 Steering：排空本 run 的中途插话，作为 user 消息注入上下文——agent 下一轮据此重定向，
            // 不重启 loop（turns 连续、同一 messages 列表、同一 run）。放在 LLM 调用之前，让插话立即生效。
            drainSteering(spec.ctx(), messages);

            // M6：LLM 调用前按需 compaction（五层）。未触发阈值时对历史前缀字节级不变（保 KV-cache）。
            Path repoDir = spec.ctx() == null ? null : spec.ctx().repoDir();
            contextManager.compact(messages, spec.systemPrompt(), repoDir,
                    ContextManager.Budget.of(spec.contextWindowTokens()), ctxState);

            LlmResponse resp = callLlm(spec, messages);
            turns++;
            if (resp == null || Boolean.FALSE.equals(resp.getSuccess())) {
                String err = resp == null ? "null 响应" : resp.getErrorMessage();
                log.warn("[loop] 终止：LLM 调用失败：{}", err);
                return result(null, turns, toolCallCount,
                        AgentRunResult.TerminationReason.LLM_ERROR, budget, messages);
            }
            budget.consume(callCost(spec, messages, resp));

            List<ToolCall> toolCalls = resp.getToolCalls();
            messages.add(assistantMessage(resp.getContent(), toolCalls));

            // 无 tool_call → 自然完成（唯一的「正常结束」信号）
            if (toolCalls == null || toolCalls.isEmpty()) {
                // 对抗性自检门：本 run 改过文件且还没自检过 → 注入一次对抗性自检提示、强制再跑一轮，
                // 逼 agent 用边界/易错输入真运行核对输出（靠执行照出 bug，绕过弱模型推理盲点）。只触发一次防死循环。
                if (adversarialReview && madeChanges && !adversarialReviewed) {
                    adversarialReviewed = true;
                    messages.add(userMessage(ADVERSARIAL_REVIEW));
                    log.info("[loop] 触发收尾前对抗性自检（改过文件），强制再跑一轮");
                    continue;
                }
                lsn.onFinalText(resp.getContent());
                return result(resp.getContent(), turns, toolCallCount,
                        AgentRunResult.TerminationReason.NO_TOOL_CALL, budget, messages);
            }

            // 只读并发 / 写串行 调度，观察回填
            List<ToolTurnScheduler.ToolResult> results = scheduler.schedule(toolCalls, spec.ctx());
            toolCallCount += toolCalls.size();
            // 标记本 run 是否改过文件（供收尾时的对抗性自检门判断）
            for (ToolCall tc : toolCalls) {
                String n = tc.getName();
                if ("write".equals(n) || "edit".equals(n) || "multi_edit".equals(n)) {
                    madeChanges = true;
                    break;
                }
            }
            for (ToolTurnScheduler.ToolResult r : results) {
                messages.add(toolMessage(r.toolCallId(), r.content()));
            }
            // M5.1 反漂移：每轮把当前 run 的 TodoWrite 清单快照重注入上下文（有清单才注入，
            // 放在 tool_result 之后、下一轮 LLM 调用之前，让"下一步聚焦哪项"始终在最近上下文里）。
            reinjectTodoSnapshot(spec.ctx(), messages);
            // 过程外显：按发起顺序回调每个工具步（observation/裁决 按 toolCallId 匹配）
            java.util.Map<String, ToolTurnScheduler.ToolResult> resById = new java.util.HashMap<>();
            for (ToolTurnScheduler.ToolResult r : results) {
                resById.put(r.toolCallId(), r);
            }
            String thought = resp.getContent();
            for (ToolCall tc : toolCalls) {
                ToolTurnScheduler.ToolResult r = resById.get(tc.getId());
                String obs = r == null ? "" : r.content();
                String verdict = r == null || r.verdict() == null ? null : r.verdict().decision().name();
                String risk = r == null || r.verdict() == null ? null
                        : String.valueOf(r.verdict().riskLevel());
                lsn.onToolStep(stepIndex++, thought, tc.getName(),
                        argsJson(tc.getArguments()), obs, verdict, risk);
            }
            // 实时改动流：把本轮新落影子区的文件改动实时 emit（影子区 vs 真目录 diff），供前端边写边高亮。
            if (spec.realtimeDiff()) {
                realtimeSinceId = realtimeChangeEmitter.emitSince(spec.ctx(), realtimeSinceId, stepIndex, lsn);
            }
        }
    }

    /**
     * 把当前 run 的 TodoWrite 清单快照作为一条 user 消息重注入（防长程漂移）。
     * 无清单则不注入（不干扰无 todo 的短任务）。ctx/runId 为空亦跳过。
     */
    private void reinjectTodoSnapshot(ToolContext ctx, List<LlmMessage> messages) {
        if (ctx == null || ctx.runId() == null) {
            return;
        }
        var todo = todoWriteTool.stateOf(ctx.runId());
        if (todo.isEmpty()) {
            return;
        }
        String snapshot = todo.renderSnapshot();
        if (snapshot != null && !snapshot.isBlank()) {
            messages.add(userMessage(snapshot));
        }
    }

    /**
     * 排空 steering 队列，把中途插话作为 user 消息注入当前 messages 列表（M7.2）。
     * 注入用醒目前缀标注为「用户中途指令」，让 agent 优先据此重定向。ctx/sessionId/runId 空则跳过。
     */
    private void drainSteering(ToolContext ctx, List<LlmMessage> messages) {
        if (ctx == null) {
            return;
        }
        List<String> pending = steeringQueue.drain(ctx.sessionId(), ctx.runId());
        for (String msg : pending) {
            messages.add(userMessage("[用户中途指令] " + msg));
        }
    }

    private LlmResponse callLlm(RunSpec spec, List<LlmMessage> messages) {
        try {
            LlmRequest req = LlmRequest.builder()
                    .modelName(spec.modelName())
                    .systemPrompt(spec.systemPrompt())
                    .messages(new ArrayList<>(messages))
                    .tools(router.definitions(spec.ctx().mode()))
                    .build();
            return llmClient.generate(req);
        } catch (Exception e) {
            log.warn("[loop] LLM 调用抛异常，降级为失败", e);
            return LlmResponse.builder().success(false).errorMessage(e.getMessage()).build();
        }
    }

    /** 本次调用的真实 token 成本：优先用 provider 上报，缺失则用估算器（含重发的上下文，贴近真实计费）。 */
    private int callCost(RunSpec spec, List<LlmMessage> messages, LlmResponse resp) {
        Integer p = resp.getPromptTokens();
        Integer c = resp.getCompletionTokens();
        if (p != null && c != null) {
            return p + c;
        }
        return tokenizer.estimate(spec.systemPrompt())
                + tokenizer.estimate(messages)
                + tokenizer.estimate(resp.getContent());
    }

    private AgentRunResult result(String finalText, int turns, int toolCallCount,
                                  AgentRunResult.TerminationReason reason,
                                  AgentBudget budget, List<LlmMessage> messages) {
        return new AgentRunResult(finalText, turns, toolCallCount, reason,
                budget.tokensSpent(), messages);
    }

    private LlmMessage userMessage(String content) {
        return LlmMessage.builder().role("user").content(content).build();
    }

    private LlmMessage assistantMessage(String content, List<ToolCall> toolCalls) {
        return LlmMessage.builder()
                .role("assistant")
                .content(content)
                .toolCalls(toolCalls != null && !toolCalls.isEmpty() ? toolCalls : null)
                .build();
    }

    private LlmMessage toolMessage(String toolCallId, String content) {
        return LlmMessage.builder().role("tool").toolCallId(toolCallId).content(content).build();
    }

    /** 工具入参序列化为紧凑 JSON（供过程外显；失败降级为 toString）。 */
    private String argsJson(java.util.Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "{}";
        }
        try {
            return ARGS_MAPPER.writeValueAsString(args);
        } catch (Exception e) {
            return String.valueOf(args);
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper ARGS_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();
}
