package com.repolens.bridge;

import com.repolens.common.result.Result;
import com.repolens.domain.entity.ChatSessionEntity;
import com.repolens.domain.entity.RepoEntity;
import com.repolens.domain.enums.PermissionMode;
import com.repolens.kernel.prompt.KernelPromptBuilder;
import com.repolens.kernel.solution.SolutionFanoutService;
import com.repolens.kernel.solution.SolutionFanoutService.FanoutSpec;
import com.repolens.kernel.solution.SolutionFanoutService.SolutionStrategy;
import com.repolens.kernel.solution.SolutionViews.SolutionSetView;
import com.repolens.kernel.spi.ReadTracker;
import com.repolens.kernel.spi.ToolContext;
import com.repolens.llm.config.LlmRuntimeConfig;
import com.repolens.mapper.ChatSessionMapper;
import com.repolens.mapper.RepoMapper;
import com.repolens.security.AuthUserId;
import com.repolens.service.support.RepoWorkspaceResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * M8 方案分支多方案对比的 REST 接缝（bridge zone）——薄转发到内核 {@link SolutionFanoutService}。
 *
 * <p>fanout：对一个「有多种合理实现」的任务并行跑 N 个 agent（各喂不同 strategy_hint、独占影子区隔离、
 * 都不落真目录），产出真实 staged 改动 + 真实指标、打分推荐；select 选定后只把选中分支合并回真目录，其余作废。
 * 真正的写只发生在选定之后——隔离哲学让多方案并行零副作用。
 */
@RestController
@RequestMapping("/api/repos/{repoId}/solution-sets")
public class KernelSolutionController {

    /** 未显式给策略时的默认三方案（制造架构多样性）。 */
    private static final List<SolutionStrategy> DEFAULT_STRATEGIES = List.of(
            new SolutionStrategy("最小改动", "用最小侵入的方式实现：尽量少改文件、少加抽象，快速可用"),
            new SolutionStrategy("清晰重构", "优先代码清晰与可维护性：可适当抽象、拆分职责、重构相关代码"),
            new SolutionStrategy("稳健防御", "优先健壮性与边界：补必要的入参校验、异常处理与容错"));

    private final SolutionFanoutService fanoutService;
    private final RepoMapper repoMapper;
    private final RepoWorkspaceResolver repoWorkspaceResolver;
    private final ChatSessionMapper chatSessionMapper;
    private final LlmRuntimeConfig llmRuntimeConfig;
    private final KernelPromptBuilder promptBuilder;

    @Value("${repolens.kernel.wall-clock-ms:180000}")
    private long wallClockMsPerBranch;

    public KernelSolutionController(SolutionFanoutService fanoutService, RepoMapper repoMapper,
                                    RepoWorkspaceResolver repoWorkspaceResolver,
                                    ChatSessionMapper chatSessionMapper, LlmRuntimeConfig llmRuntimeConfig,
                                    KernelPromptBuilder promptBuilder) {
        this.fanoutService = fanoutService;
        this.repoMapper = repoMapper;
        this.repoWorkspaceResolver = repoWorkspaceResolver;
        this.chatSessionMapper = chatSessionMapper;
        this.llmRuntimeConfig = llmRuntimeConfig;
        this.promptBuilder = promptBuilder;
    }

    /** 请求体：触发多方案。strategies 可空（用默认三方案）。 */
    public record FanoutRequest(String question, Long sessionId, List<StrategyDTO> strategies) {
    }

    public record StrategyDTO(String label, String hint) {
    }

    /** 请求体：选定分支。 */
    public record SelectRequest(Long branchId) {
    }

    /** 触发多方案 fanout（并行跑 N 个 agent，产真实指标 + 推荐）。同步返回，耗时随分支数与模型而定。 */
    @PostMapping("/fanout")
    public Result<SolutionSetView> fanout(@AuthUserId Long userId,
                                          @PathVariable Long repoId,
                                          @RequestBody FanoutRequest req) {
        RepoEntity repo = repoMapper.selectById(repoId);
        if (repo == null) {
            throw new IllegalStateException("仓库不存在: " + repoId);
        }
        // 与 agent 写、前端读一致用「读目录」（file:// 本地仓库=真实项目目录）。
        Path repoDir = repoWorkspaceResolver.resolveReadDirectory(repo);
        Long sessionId = resolveSessionId(userId, repoId, req.sessionId(), req.question());
        String modelName = llmRuntimeConfig.getModelName();

        // 构建共享系统提示词（仅用于提示，ctx 无需影子区）
        ToolContext promptCtx = new ToolContext(repoId, sessionId, null, repoDir, null,
                new ReadTracker(), PermissionMode.DEFAULT, modelName);
        String systemPrompt = promptBuilder.build(promptCtx, repo.getRepoName(), repo.getBranchName());

        List<SolutionStrategy> strategies = toStrategies(req.strategies());
        FanoutSpec spec = new FanoutSpec(repoId, sessionId, repoDir, req.question(),
                systemPrompt, modelName, strategies, 0, wallClockMsPerBranch);
        return Result.success(fanoutService.fanout(spec));
    }

    /** 拉某方案组视图（聊天卡组 + 可视化对比窗共读）。 */
    @GetMapping("/{setId}")
    public Result<SolutionSetView> get(@AuthUserId Long userId,
                                       @PathVariable Long repoId,
                                       @PathVariable Long setId) {
        return Result.success(fanoutService.get(setId));
    }

    /** 选定一个分支：只把它合并回真目录，其余分支 DISCARDED。 */
    @PostMapping("/{setId}/select")
    public Result<SolutionSetView> select(@AuthUserId Long userId,
                                          @PathVariable Long repoId,
                                          @PathVariable Long setId,
                                          @RequestBody SelectRequest req) {
        return Result.success(fanoutService.select(setId, req.branchId()));
    }

    private List<SolutionStrategy> toStrategies(List<StrategyDTO> dtos) {
        if (dtos == null || dtos.isEmpty()) {
            return DEFAULT_STRATEGIES;
        }
        List<SolutionStrategy> out = new ArrayList<>();
        for (StrategyDTO d : dtos) {
            out.add(new SolutionStrategy(d.label(), d.hint()));
        }
        return out;
    }

    private Long resolveSessionId(Long userId, Long repoId, Long sessionId, String question) {
        if (sessionId != null) {
            return sessionId;
        }
        ChatSessionEntity session = new ChatSessionEntity();
        session.setUserId(userId);
        session.setRepoId(repoId);
        String title = question == null ? "多方案" : question.trim();
        if (title.length() > 30) {
            title = title.substring(0, 30);
        }
        session.setTitle(title);
        chatSessionMapper.insert(session);
        return session.getId();
    }
}
