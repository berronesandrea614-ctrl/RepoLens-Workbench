package com.repolens.bridge;

import com.repolens.domain.dto.chat.CodeAnswerRequest;
import com.repolens.domain.entity.ChatSessionEntity;
import com.repolens.domain.entity.RepoEntity;
import com.repolens.domain.enums.PermissionMode;
import com.repolens.domain.vo.AgentStepVO;
import com.repolens.domain.vo.CodeAnswerVO;
import com.repolens.kernel.loop.AgentLoopExecutor;
import com.repolens.kernel.loop.AgentRunResult;
import com.repolens.kernel.loop.RunListener;
import com.repolens.kernel.prompt.KernelPromptBuilder;
import com.repolens.kernel.rules.HierarchicalRulesLoader;
import com.repolens.kernel.shadow.ShadowWorkspaceManager;
import com.repolens.kernel.shadow.ShadowWorkspaceManager.ShadowHandle;
import com.repolens.kernel.spi.ReadTracker;
import com.repolens.kernel.spi.ToolContext;
import com.repolens.llm.config.LlmRuntimeConfig;
import com.repolens.mapper.ChatSessionMapper;
import com.repolens.mapper.RepoMapper;
import com.repolens.service.AgentRunService;
import com.repolens.service.support.RepoWorkspaceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 内核接主链路 bridge（M3 收官：把新写的 {@code kernel} agent 主循环接进真实 app 请求链路）。
 *
 * <p>取代旧 god class {@code CodeAnswerServiceImpl} 的 agent 编码路径——但采用<b>增量替换</b>：
 * 由 feature flag {@code repolens.kernel.agent-enabled} 控制，默认关闭走旧路径，打开后 code 模式
 * 走本 bridge，前端 SSE 契约（meta/token/step/done/error 事件）保持一致、零改动复用。
 *
 * <p>编排：解析真目录 → {@code AgentRunService.begin} 预占 runId → 影子工作区 → 组装
 * {@link ToolContext} → 跑内核 {@link AgentLoopExecutor}（带 {@link RunListener} 逐步 emit）→
 * {@code finish} 收尾 → transcript 映射成 {@link AgentStepVO} → 建 {@link CodeAnswerVO}。
 */
@Service
public class KernelAgentService {

    private static final Logger log = LoggerFactory.getLogger(KernelAgentService.class);

    /** 后台执行 SSE 推送（SseEmitter 契约：本方法拿到 emitter 立即返回，实际工作在后台线程）。 */
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "kernel-agent");
        t.setDaemon(true);
        return t;
    });

    private final RepoMapper repoMapper;
    private final RepoWorkspaceResolver repoWorkspaceResolver;
    private final ChatSessionMapper chatSessionMapper;
    private final AgentRunService agentRunService;
    private final ShadowWorkspaceManager shadowManager;
    private final AgentLoopExecutor agentLoopExecutor;
    private final LlmRuntimeConfig llmRuntimeConfig;
    private final KernelPromptBuilder promptBuilder;
    private final HierarchicalRulesLoader rulesLoader;
    private final AskUserService askUserService;
    private final com.repolens.kernel.git.GitService gitService;
    private final com.repolens.kernel.skill.SkillSlashResolver skillSlashResolver;
    private final com.repolens.mapper.ChatMessageMapper chatMessageMapper;
    /** 内核改动 → 左侧工具应用表的数据同步桥（run 收尾后调，让左侧工具看得到 AI 改动）。 */
    private final KernelAppBridge kernelAppBridge;

    /** 直接编辑模式（默认开）：agent 直接改工作目录、git 回溯，不走影子区隔离。关闭则回到影子区+审批合并。 */
    @Value("${repolens.kernel.direct-edit:true}")
    private boolean directEdit;

    /** token 预算（≤0 不限）。 */
    @Value("${repolens.kernel.max-tokens:0}")
    private long maxTokens;
    /** 墙钟预算毫秒（≤0 不限）。 */
    @Value("${repolens.kernel.wall-clock-ms:120000}")
    private long wallClockMs;
    /** 模型上下文窗口 token 数（M6 compaction 触发依据；≤0 不做上下文压缩）。 */
    @Value("${repolens.kernel.context-window-tokens:128000}")
    private int contextWindowTokens;

    public KernelAgentService(RepoMapper repoMapper,
                              RepoWorkspaceResolver repoWorkspaceResolver,
                              ChatSessionMapper chatSessionMapper,
                              AgentRunService agentRunService,
                              ShadowWorkspaceManager shadowManager,
                              AgentLoopExecutor agentLoopExecutor,
                              LlmRuntimeConfig llmRuntimeConfig,
                              KernelPromptBuilder promptBuilder,
                              HierarchicalRulesLoader rulesLoader,
                              AskUserService askUserService,
                              com.repolens.kernel.git.GitService gitService,
                              com.repolens.kernel.skill.SkillSlashResolver skillSlashResolver,
                              com.repolens.mapper.ChatMessageMapper chatMessageMapper,
                              KernelAppBridge kernelAppBridge) {
        this.repoMapper = repoMapper;
        this.repoWorkspaceResolver = repoWorkspaceResolver;
        this.chatSessionMapper = chatSessionMapper;
        this.agentRunService = agentRunService;
        this.shadowManager = shadowManager;
        this.agentLoopExecutor = agentLoopExecutor;
        this.llmRuntimeConfig = llmRuntimeConfig;
        this.promptBuilder = promptBuilder;
        this.rulesLoader = rulesLoader;
        this.askUserService = askUserService;
        this.gitService = gitService;
        this.skillSlashResolver = skillSlashResolver;
        this.chatMessageMapper = chatMessageMapper;
        this.kernelAppBridge = kernelAppBridge;
    }

    /**
     * 流式编码：内核 agent 主循环驱动，SSE 逐步推送轨迹与最终答案。
     * 与旧 {@code CodeAnswerService.answerStream} 同端点契约（同 emitter、同事件名）。
     */
    public void answerStream(Long repoId, Long userId, CodeAnswerRequest request, SseEmitter emitter) {
        pool.submit(() -> {
            // 本次流绑定的会话 id（onSession 回调时填），用于 finally 解绑 askUser 通道。
            final Long[] boundSession = {null};
            try {
                String modelName = llmRuntimeConfig.getModelName();
                emit(emitter, "meta", Map.of("references", List.of(),
                        "modelName", modelName == null ? "" : modelName));
                // 逐步把内核过程 emit 成前端已认的 step/token 事件（客户端断连不中断 loop）
                RunListener sse = new RunListener() {
                    @Override
                    public void onSession(Long sessionId, Long runId) {
                        // 绑定 askUser 通道：agent 调 askUser 时，问题沿此 emit "ask" 事件推给前端。
                        boundSession[0] = sessionId;
                        askUserService.bind(sessionId, ev -> emitQuiet(emitter, "ask", ev));
                    }

                    @Override
                    public void onToolStep(int index, String thought, String toolName,
                                           String toolArgs, String observation) {
                        Map<String, Object> step = new LinkedHashMap<>();
                        step.put("stepIndex", index);
                        step.put("thought", thought);
                        step.put("toolName", toolName);
                        step.put("toolArgs", toolArgs);
                        step.put("observation", observation);
                        emitQuiet(emitter, "step", step);
                    }

                    @Override
                    public void onFileChange(int stepIndex, Long sessionId, String filePath,
                                             String changeType, String before, String after) {
                        Map<String, Object> ch = new LinkedHashMap<>();
                        ch.put("stepIndex", stepIndex);
                        ch.put("sessionId", sessionId);
                        ch.put("filePath", filePath);
                        ch.put("changeType", changeType);
                        ch.put("before", before);
                        ch.put("after", after);
                        emitQuiet(emitter, "file_change", ch);
                    }

                    @Override
                    public void onFinalText(String text) {
                        if (text != null && !text.isEmpty()) {
                            emitQuiet(emitter, "token", Map.of("text", text));
                        }
                    }
                };
                CodeAnswerVO vo = runAgent(repoId, userId, request, sse);
                emit(emitter, "done", vo);
                emitter.complete();
            } catch (Exception e) {
                log.warn("[kernel-agent] 流式执行失败 repoId={}", repoId, e);
                try {
                    emit(emitter, "error", Map.of("message", e.getMessage() == null ? "内部错误" : e.getMessage()));
                    emitter.complete();
                } catch (Exception ignore) {
                    emitter.completeWithError(e);
                }
            } finally {
                // 无论正常/异常，解绑 askUser 通道并唤醒可能仍挂起的提问（防 loop 线程泄漏）。
                askUserService.unbind(boundSession[0]);
            }
        });
    }

    /**
     * 纯编排（同步、无 SSE）：跑内核 agent 主循环并产出 {@link CodeAnswerVO}。
     * SSE 由 {@link #answerStream} 包裹；本方法可被直接调用/单测（真实行为可验）。
     *
     * @param listener 过程监听（可空）：调用方可借此逐步 emit；本方法内部另行累积 steps 进 VO。
     */
    public CodeAnswerVO runAgent(Long repoId, Long userId, CodeAnswerRequest request, RunListener listener) {
        String question = request.getQuestion();
        Long sessionId = resolveSessionId(repoId, userId, request.getSessionId(), question);
        // 先加载本会话历史对话（多轮记忆）——必须在存本轮用户消息之前，否则会把当前提问也算进历史。
        List<com.repolens.llm.model.LlmMessage> priorMessages = loadHistory(sessionId);
        // 持久化用户消息（先存，即便本次 run 失败历史里也留得住这条提问）——治「历史全是空会话」。
        saveMessage(sessionId, "USER", question, null);

        RepoEntity repo = repoMapper.selectById(repoId);
        if (repo == null) {
            throw new IllegalStateException("仓库不存在: " + repoId);
        }
        // 用「读目录」：对 file:// 本地导入的仓库返回用户打开的真实项目目录，保证 agent 改动、accept 合并、
        // 前端读文件三者落在同一目录（否则写快照、读原地会脱节→前端「File not found」）。
        Path repoDir = repoWorkspaceResolver.resolveReadDirectory(repo);

        // 斜杠路由（user-invoked）：/cmd → .claude/commands 展开；/skill-name → 触发 Skill 工具加载该 skill。
        // 非斜杠/未命中返回 null，回落原始 question。原始 question 仍用于会话标题与 VO 回显。
        String promptForLoop = question;
        try {
            String routed = skillSlashResolver.route(question, repoDir);
            if (routed != null && !routed.isBlank()) {
                promptForLoop = routed;
            }
        } catch (Exception e) {
            log.warn("[kernel-agent] 斜杠路由失败，按原输入处理: {}", e.getMessage());
        }

        PermissionMode mode = request.getPermissionMode() == null
                ? PermissionMode.DEFAULT : request.getPermissionMode();
        Long runId = agentRunService.begin(userId, repoId, sessionId, mode.name());
        // 通知 bridge 会话/run 已建立：answerStream 借此把 askUser 的 emit 通道绑定到本次 SSE emitter。
        if (listener != null) {
            listener.onSession(sessionId, runId);
        }

        // 直接编辑模式：影子根直接指向工作目录（不做 CoW 克隆），agent 的写就是直接改工作目录；
        // 先建 git 基线作为撤销安全网。关闭该模式则回到「影子区隔离 + 审批合并」。
        // shadow.id()==null 是「直接模式」的哨兵（emitter/review 据此走 git 撤销而非影子回搬）。
        ShadowHandle shadow;
        if (directEdit) {
            gitService.ensureBaseline(repoDir);
            shadow = new ShadowHandle(null, repoDir, "DIRECT");
        } else {
            shadow = shadowManager.resolveOrCreate(repoId, sessionId, runId, repoDir);
        }
        String modelName = llmRuntimeConfig.getModelName();
        ToolContext ctx = new ToolContext(repoId, sessionId, runId, repoDir, shadow, new ReadTracker(), mode, modelName);
        String systemPrompt = promptBuilder.build(ctx, repo.getRepoName(), repo.getBranchName(), mode);
        // M6：拼接记忆层级规则块（CLAUDE.md/AGENTS.md 层级 + @import + .claude/rules 作用域）到系统提示词。
        // 无作用域上下文时只注入层级+全局规则；加载失败降级为空块，不影响主链路。
        try {
            String rulesBlock = rulesLoader.load(repoDir, List.of());
            if (rulesBlock != null && !rulesBlock.isBlank()) {
                systemPrompt = systemPrompt + "\n" + rulesBlock;
            }
        } catch (Exception e) {
            log.warn("[kernel-agent] 记忆层级规则加载失败，跳过 repoDir={}: {}", repoDir, e.getMessage());
        }

        List<AgentStepVO> steps = new CopyOnWriteArrayList<>();
        // 组合监听：内部累积 steps 进 VO + 转发给外部 listener（SSE）
        RunListener composite = new RunListener() {
            @Override
            public void onToolStep(int index, String thought, String toolName,
                                   String toolArgs, String observation) {
                onToolStep(index, thought, toolName, toolArgs, observation, null, null);
            }

            @Override
            public void onToolStep(int index, String thought, String toolName, String toolArgs,
                                   String observation, String verdict, String riskLevel) {
                // §3.7 可视化外显：被拒的步把 decision/reason 填进 permissionVerdict
                String pv = verdict == null ? null
                        : (observation != null && !"ALLOW".equals(verdict) ? verdict + "：" + observation : verdict);
                steps.add(AgentStepVO.builder()
                        .stepIndex(index).thought(thought).toolName(toolName)
                        .toolArgs(toolArgs).observation(observation)
                        .permissionVerdict(pv).riskLevel(riskLevel).build());
                if (listener != null) {
                    listener.onToolStep(index, thought, toolName, toolArgs, observation, verdict, riskLevel);
                }
            }

            @Override
            public void onFileChange(int stepIndex, Long sessionId, String filePath,
                                     String changeType, String before, String after) {
                if (listener != null) {
                    listener.onFileChange(stepIndex, sessionId, filePath, changeType, before, after);
                }
            }

            @Override
            public void onFinalText(String text) {
                if (listener != null) {
                    listener.onFinalText(text);
                }
            }
        };

        boolean realtimeDiff = Boolean.TRUE.equals(request.getRealtimeDiff());
        AgentLoopExecutor.RunSpec spec = new AgentLoopExecutor.RunSpec(
                systemPrompt, promptForLoop, modelName, ctx, maxTokens, wallClockMs, contextWindowTokens,
                realtimeDiff, priorMessages);

        long t0 = System.currentTimeMillis();
        AgentRunResult result = agentLoopExecutor.run(spec, composite);
        long wall = System.currentTimeMillis() - t0;

        String answer = result.finalText() == null ? "" : result.finalText();
        safeFinish(runId, answer, result.toolCallCount(), wall);
        // 持久化助手回答——历史会话据此回放（否则历史全是空会话）。
        if (!answer.isBlank()) {
            saveMessage(sessionId, "ASSISTANT", answer, null);
        }

        // 数据同步桥：把本次 run 的 AI 改动喂给左侧工具应用表（file_change_log 落库同步、重算异步）。
        // 整体 fail-safe：内部自吞异常、重活丢后台线程，绝不阻塞本方法返回、绝不因失败让 run 失败。
        try {
            kernelAppBridge.syncAfterRun(repoId, userId, sessionId, runId);
            // 需求归纳：把本次问答归纳成需求卡片入需求流（异步、LLM 自动过滤无意义追问）。
            kernelAppBridge.extractRequirementAsync(repoId, userId, sessionId, runId, question, answer);
        } catch (Exception e) {
            log.warn("[kernel-agent] 应用表同步桥调用失败（不影响回答）runId={}: {}", runId, e.getMessage());
        }

        return CodeAnswerVO.builder()
                .repoId(repoId)
                .sessionId(sessionId)
                .question(question)
                .answer(answer)
                .agentMode(true)
                .agentSteps(steps)
                .agentIterations(result.turns())
                .agentToolCalls(result.toolCallCount())
                .agentRunId(runId)
                .modelName(modelName)
                .completionTokens((int) result.tokensSpent())
                .costMs(wall)
                .shadowActive(!directEdit)
                .build();
    }

    private Long resolveSessionId(Long repoId, Long userId, Long sessionId, String question) {
        if (sessionId != null) {
            return sessionId;
        }
        ChatSessionEntity session = new ChatSessionEntity();
        session.setUserId(userId);
        session.setRepoId(repoId);
        String title = question == null ? "" : question.trim();
        if (title.length() > 30) {
            title = title.substring(0, 30);
        }
        session.setTitle(title);
        chatSessionMapper.insert(session);
        return session.getId();
    }

    /** 每次 run 最多带入的历史对话轮数（防历史过长撑爆上下文）。 */
    private static final int HISTORY_MAX_MESSAGES = 20;

    /** 加载本会话历史对话，映射成 LLM 多轮消息（多轮记忆）。fail-safe：失败返回空。 */
    private List<com.repolens.llm.model.LlmMessage> loadHistory(Long sessionId) {
        if (sessionId == null) {
            return List.of();
        }
        try {
            var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.repolens.domain.entity.ChatMessageEntity>()
                    .eq(com.repolens.domain.entity.ChatMessageEntity::getSessionId, sessionId)
                    .orderByAsc(com.repolens.domain.entity.ChatMessageEntity::getId);
            List<com.repolens.domain.entity.ChatMessageEntity> rows = chatMessageMapper.selectList(wrapper);
            if (rows == null || rows.isEmpty()) {
                return List.of();
            }
            if (rows.size() > HISTORY_MAX_MESSAGES) {
                rows = rows.subList(rows.size() - HISTORY_MAX_MESSAGES, rows.size());
            }
            List<com.repolens.llm.model.LlmMessage> out = new java.util.ArrayList<>();
            for (com.repolens.domain.entity.ChatMessageEntity m : rows) {
                String content = m.getContent();
                if (content == null || content.isBlank()) {
                    continue;
                }
                String role = "ASSISTANT".equalsIgnoreCase(m.getRole()) ? "assistant" : "user";
                out.add(com.repolens.llm.model.LlmMessage.builder().role(role).content(content).build());
            }
            return out;
        } catch (Exception e) {
            log.warn("[kernel-agent] 加载会话历史失败 sessionId={}: {}", sessionId, e.getMessage());
            return List.of();
        }
    }

    /** 落库一条聊天消息（fail-safe：失败只记日志，不影响主流程）。历史会话据此回放。 */
    private void saveMessage(Long sessionId, String role, String content, String referencesJson) {
        if (sessionId == null || content == null) {
            return;
        }
        try {
            com.repolens.domain.entity.ChatMessageEntity m = new com.repolens.domain.entity.ChatMessageEntity();
            m.setSessionId(sessionId);
            m.setRole(role);
            m.setContent(content);
            m.setReferencesJson(referencesJson);
            chatMessageMapper.insert(m);
        } catch (Exception e) {
            log.warn("[kernel-agent] 消息落库失败 sessionId={} role={}: {}", sessionId, role, e.getMessage());
        }
    }

    private void safeFinish(Long runId, String answer, int toolTurns, long wallMs) {
        if (runId == null) {
            return;
        }
        try {
            agentRunService.finish(runId, answer, toolTurns, wallMs);
        } catch (Exception e) {
            log.warn("[kernel-agent] finish 落库失败 runId={}: {}", runId, e.getMessage());
        }
    }

    private void emit(SseEmitter emitter, String name, Object payload) throws java.io.IOException {
        emitter.send(SseEmitter.event().name(name).data(payload, MediaType.APPLICATION_JSON));
    }

    /** emit 但吞掉 IO 异常（过程事件，客户端断连不应中断 loop）。 */
    private void emitQuiet(SseEmitter emitter, String name, Object payload) {
        try {
            emit(emitter, name, payload);
        } catch (Exception e) {
            log.debug("[kernel-agent] emit {} 失败(客户端可能已断): {}", name, e.getMessage());
        }
    }
}
