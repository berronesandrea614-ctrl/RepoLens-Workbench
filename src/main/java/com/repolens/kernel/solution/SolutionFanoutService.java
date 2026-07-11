package com.repolens.kernel.solution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.repolens.domain.enums.PermissionMode;
import com.repolens.kernel.loop.AgentLoopExecutor;
import com.repolens.kernel.loop.AgentRunResult;
import com.repolens.kernel.persistence.entity.RkFileChangeEntity;
import com.repolens.kernel.persistence.entity.RkSolutionBranchEntity;
import com.repolens.kernel.persistence.entity.RkSolutionSetEntity;
import com.repolens.kernel.persistence.mapper.RkFileChangeMapper;
import com.repolens.kernel.persistence.mapper.RkSolutionBranchMapper;
import com.repolens.kernel.persistence.mapper.RkSolutionSetMapper;
import com.repolens.kernel.shadow.FileChangeRecorder;
import com.repolens.kernel.shadow.ShadowWorkspaceManager;
import com.repolens.kernel.shadow.ShadowWorkspaceManager.ShadowHandle;
import com.repolens.kernel.spi.ReadTracker;
import com.repolens.kernel.spi.ToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * M8 · 方案分支多方案对比引擎（超越层 B7 后端引擎侧）。
 *
 * <p>核心：同一个「有多种合理实现」的任务，<b>并行跑 N 个 {@link AgentLoopExecutor}</b>，每个分支喂不同
 * {@code strategy_hint}、独占一个影子工作区（{@link ShadowWorkspaceManager} 隔离克隆），各自把改动落到
 * <b>自己的影子区、都不落真目录</b>。跑完按真实 staged 改动统计每分支的指标（改动面/行数增删/token/轮次），
 * 归一化打分推荐；用户 {@link #select} 选定后只把选中分支的改动合并回真目录，其余分支 DISCARDED（零副作用）。
 *
 * <p>为什么这是差异化点：Claude/Cursor 的多方案是「多段文字描述」，本引擎是「多份真实 staged 改动」，
 * 指标 {@code metric_kind=REAL} 是真跑出来的（不是预估），选谁就真落地谁——纯文本 side-by-side 给不出。
 *
 * <p>诚实边界（P1）：<b>不物理跑测试</b>，故 {@code verified} 保持 {@code null}（宁可空着不谎报绿灯，
 * 对齐内核 failing-until-tested 纪律）；blast radius/理解债务等依赖调用图的维度归隔壁窗口，此处不算不碰。
 * 每分支不落盘，故并行安全、成本可控（默认 2–4 个）。
 */
@Service
public class SolutionFanoutService {

    private static final Logger log = LoggerFactory.getLogger(SolutionFanoutService.class);

    /** 分支收尾说明入库上限（对齐 DDL final_text VARCHAR(2000)）。 */
    private static final int FINAL_TEXT_CAP = 2000;
    /** 并行分支数硬上限（防误配爆资源；设计规格默认 2、上限 4）。 */
    private static final int MAX_PARALLEL = 4;

    private final AgentLoopExecutor loopExecutor;
    private final ShadowWorkspaceManager shadowManager;
    private final FileChangeRecorder fileChangeRecorder;
    private final RkFileChangeMapper fileChangeMapper;
    private final RkSolutionSetMapper setMapper;
    private final RkSolutionBranchMapper branchMapper;

    private final ExecutorService pool = Executors.newCachedThreadPool(new ThreadFactory() {
        private final AtomicInteger seq = new AtomicInteger();
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "kernel-solution-fanout-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    public SolutionFanoutService(AgentLoopExecutor loopExecutor,
                                 ShadowWorkspaceManager shadowManager,
                                 FileChangeRecorder fileChangeRecorder,
                                 RkFileChangeMapper fileChangeMapper,
                                 RkSolutionSetMapper setMapper,
                                 RkSolutionBranchMapper branchMapper) {
        this.loopExecutor = loopExecutor;
        this.shadowManager = shadowManager;
        this.fileChangeRecorder = fileChangeRecorder;
        this.fileChangeMapper = fileChangeMapper;
        this.setMapper = setMapper;
        this.branchMapper = branchMapper;
    }

    /** 一个方案策略：给分支的名字 + 架构提示（制造多样性，喂进该分支的 prompt）。 */
    public record SolutionStrategy(String label, String hint) {
    }

    /**
     * 一次 fanout 的输入。
     *
     * @param repoId               仓库 id
     * @param sessionId            会话 id
     * @param repoDir              真目录根（每分支影子区从此克隆；select 合并锚点）
     * @param question             原始任务
     * @param systemPrompt         系统提示词（各分支共用，差异只在 strategy hint）
     * @param modelName            模型名
     * @param strategies           N 个策略（2–4；超出上限截断）
     * @param maxTokensPerBranch   单分支 token 预算（≤0 不限）
     * @param wallClockMsPerBranch 单分支墙钟预算毫秒（≤0 不限）
     */
    public record FanoutSpec(Long repoId, Long sessionId, Path repoDir, String question,
                             String systemPrompt, String modelName, List<SolutionStrategy> strategies,
                             long maxTokensPerBranch, long wallClockMsPerBranch) {
    }

    /**
     * 并行产出多个方案分支：建 set → 每策略一个隔离影子区并行跑 agent → 统计真实指标 → 打分推荐。
     * 各分支互不落盘，异常分支标 FAILED 不拖垮其它分支。
     */
    public SolutionViews.SolutionSetView fanout(FanoutSpec spec) {
        List<SolutionStrategy> strategies = spec.strategies();
        if (strategies == null || strategies.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个方案策略");
        }
        if (strategies.size() > MAX_PARALLEL) {
            log.warn("[solution] 策略数 {} 超上限 {}，截断", strategies.size(), MAX_PARALLEL);
            strategies = strategies.subList(0, MAX_PARALLEL);
        }

        RkSolutionSetEntity set = new RkSolutionSetEntity();
        set.setRepoId(spec.repoId());
        set.setSessionId(spec.sessionId());
        set.setQuestion(truncate(spec.question(), 1000));
        set.setRepoDir(spec.repoDir().toString());
        set.setEngine("NATIVE");
        set.setStatus("GENERATING");
        set.setVariantCount(0);
        setMapper.insert(set);
        Long setId = set.getId();

        List<CompletableFuture<Long>> futures = new ArrayList<>();
        for (int i = 0; i < strategies.size(); i++) {
            final int variantIndex = i;
            final SolutionStrategy strategy = strategies.get(i);
            futures.add(CompletableFuture.supplyAsync(
                    () -> runBranch(spec, setId, variantIndex, strategy), pool));
        }
        // 等所有分支跑完（每个分支内部已 fail-safe，join 不会因单分支异常整体抛出）
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        List<RkSolutionBranchEntity> branches = loadBranches(setId);
        Long recommendedId = recommend(branches);

        set.setVariantCount(branches.size());
        set.setRecommendedBranchId(recommendedId);
        set.setStatus("READY");
        setMapper.updateById(set);

        log.info("[solution] set #{} 产出 {} 个方案，推荐分支 #{}", setId, branches.size(), recommendedId);
        return get(setId);
    }

    /**
     * 单个分支：独占影子区 + 独占 runId 跑 agent，把改动 stage 到自己的影子区，统计真实指标回填。
     * 全程 fail-safe：任何异常都标 FAILED 落库、返回 branchId，不外抛（不拖垮兄弟分支）。
     */
    private Long runBranch(FanoutSpec spec, Long setId, int variantIndex, SolutionStrategy strategy) {
        RkSolutionBranchEntity branch = new RkSolutionBranchEntity();
        branch.setSetId(setId);
        branch.setRepoId(spec.repoId());
        branch.setSessionId(spec.sessionId());
        branch.setLabel(truncate(strategy.label(), 128));
        branch.setStrategyHint(truncate(strategy.hint(), 512));
        branch.setVariantIndex(variantIndex);
        branch.setMetricKind("REAL");
        branch.setStatus("STAGED");
        branch.setFilesChanged(0);
        branch.setLinesAdded(0);
        branch.setLinesRemoved(0);
        branch.setTokensSpent(0L);
        branch.setTurns(0);
        branchMapper.insert(branch);
        Long branchId = branch.getId();
        // 用 branchId 作为本分支独占 runId（自命名空间，避免与真 agent_run 冲突）
        Long runId = branchId;
        branch.setRunId(runId);

        try {
            ShadowHandle shadow = shadowManager.create(spec.repoId(), spec.sessionId(), runId, spec.repoDir());
            branch.setShadowId(shadow.id());

            ToolContext ctx = new ToolContext(spec.repoId(), spec.sessionId(), runId, spec.repoDir(),
                    shadow, new ReadTracker(), PermissionMode.DEFAULT, spec.modelName());
            String userPrompt = buildBranchPrompt(spec.question(), strategy);
            AgentLoopExecutor.RunSpec runSpec = new AgentLoopExecutor.RunSpec(
                    spec.systemPrompt(), userPrompt, spec.modelName(), ctx,
                    spec.maxTokensPerBranch(), spec.wallClockMsPerBranch());

            AgentRunResult result = loopExecutor.run(runSpec);

            LineDelta delta = measureChanges(shadow, spec.repoDir());
            branch.setFilesChanged(delta.files());
            branch.setLinesAdded(delta.added());
            branch.setLinesRemoved(delta.removed());
            branch.setTokensSpent(result.tokensSpent());
            branch.setTurns(result.turns());
            branch.setTerminationReason(result.terminationReason().name());
            branch.setFinalText(truncate(result.finalText(), FINAL_TEXT_CAP));
            branch.setStatus(result.terminationReason() == AgentRunResult.TerminationReason.LLM_ERROR
                    ? "FAILED" : "STAGED");
            branchMapper.updateById(branch);
        } catch (Exception e) {
            log.warn("[solution] 分支 #{}（{}）执行异常，标 FAILED", branchId, strategy.label(), e);
            branch.setStatus("FAILED");
            branch.setTerminationReason("EXCEPTION");
            branch.setFinalText(truncate("分支执行异常：" + e.getMessage(), FINAL_TEXT_CAP));
            try {
                branchMapper.updateById(branch);
            } catch (Exception ignore) {
                // 落库都失败则放弃，不再级联
            }
        }
        return branchId;
    }

    /**
     * 选定一个分支：把它的 staged 改动合并回真目录，其余分支 DISCARDED（改动作废 + 影子区丢弃，零副作用）。
     * 只有 STAGED 分支可选（FAILED 不可选）。
     */
    public SolutionViews.SolutionSetView select(Long setId, Long branchId) {
        RkSolutionSetEntity set = setMapper.selectById(setId);
        if (set == null) {
            throw new IllegalArgumentException("方案组不存在: " + setId);
        }
        List<RkSolutionBranchEntity> branches = loadBranches(setId);
        RkSolutionBranchEntity chosen = branches.stream()
                .filter(b -> b.getId().equals(branchId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("分支 " + branchId + " 不属于方案组 " + setId));
        if (!"STAGED".equals(chosen.getStatus())) {
            throw new IllegalStateException("分支 " + branchId + " 状态为 " + chosen.getStatus() + "，不可选定");
        }

        Path repoDir = Path.of(set.getRepoDir());
        try {
            ShadowHandle chosenShadow = new ShadowHandle(chosen.getShadowId(), shadowRootOf(chosen), "ACTIVE");
            fileChangeRecorder.mergeAll(chosenShadow, repoDir);
        } catch (Exception e) {
            throw new IllegalStateException("合并选中分支 " + branchId + " 失败: " + e.getMessage(), e);
        }
        chosen.setStatus("SELECTED");
        branchMapper.updateById(chosen);

        for (RkSolutionBranchEntity other : branches) {
            if (other.getId().equals(branchId) || "FAILED".equals(other.getStatus())) {
                continue;
            }
            discardBranch(other);
        }

        set.setSelectedBranchId(branchId);
        set.setStatus("SELECTED");
        setMapper.updateById(set);
        log.info("[solution] set #{} 选定分支 #{}，其余分支已 DISCARDED", setId, branchId);
        return get(setId);
    }

    /** 组装一个方案组的完整视图（聊天卡组 + 可视化对比窗共读）。 */
    public SolutionViews.SolutionSetView get(Long setId) {
        RkSolutionSetEntity set = setMapper.selectById(setId);
        if (set == null) {
            throw new IllegalArgumentException("方案组不存在: " + setId);
        }
        List<RkSolutionBranchEntity> branches = loadBranches(setId);
        Long recommendedId = set.getRecommendedBranchId();
        List<SolutionViews.SolutionBranchView> views = new ArrayList<>();
        for (RkSolutionBranchEntity b : branches) {
            views.add(new SolutionViews.SolutionBranchView(
                    b.getId(), b.getLabel(), b.getStrategyHint(), b.getVariantIndex(),
                    b.getMetricKind(), b.getStatus(),
                    nz(b.getFilesChanged()), nz(b.getLinesAdded()), nz(b.getLinesRemoved()),
                    b.getTokensSpent() == null ? 0L : b.getTokensSpent(), nz(b.getTurns()),
                    b.getVerified(), b.getTerminationReason(), b.getFinalText(),
                    b.getId().equals(recommendedId)));
        }
        return new SolutionViews.SolutionSetView(set.getId(), set.getRepoId(), set.getSessionId(),
                set.getEngine(), set.getStatus(), set.getSelectedBranchId(), recommendedId, views);
    }

    // ---- 内部：作废分支 ----

    private void discardBranch(RkSolutionBranchEntity branch) {
        if (branch.getShadowId() != null) {
            fileChangeMapper.update(null, new LambdaUpdateWrapper<RkFileChangeEntity>()
                    .eq(RkFileChangeEntity::getShadowId, branch.getShadowId())
                    .eq(RkFileChangeEntity::getStatus, "WRITTEN_TO_SHADOW")
                    .set(RkFileChangeEntity::getStatus, "DISCARDED"));
            try {
                shadowManager.discard(branch.getShadowId());
            } catch (Exception e) {
                log.warn("[solution] 丢弃影子区 #{} 失败: {}", branch.getShadowId(), e.getMessage());
            }
        }
        branch.setStatus("DISCARDED");
        branchMapper.updateById(branch);
    }

    // ---- 内部：真实改动度量 ----

    /** 一个分支相对真目录基线的行级增删汇总。 */
    private record LineDelta(int files, int added, int removed) {
    }

    /**
     * 按该分支影子区里 {@code WRITTEN_TO_SHADOW} 的改动统计真实指标：
     * 改动文件去重计数；每个文件读影子区最终内容 vs 真目录基线做 LCS 行级 diff，累加增删行。
     */
    private LineDelta measureChanges(ShadowHandle shadow, Path repoDir) {
        List<RkFileChangeEntity> changes = fileChangeMapper.selectList(
                new LambdaQueryWrapper<RkFileChangeEntity>()
                        .eq(RkFileChangeEntity::getShadowId, shadow.id())
                        .eq(RkFileChangeEntity::getStatus, "WRITTEN_TO_SHADOW"));
        Set<String> touched = new LinkedHashSet<>();
        for (RkFileChangeEntity c : changes) {
            touched.add(c.getFilePath());
        }
        int added = 0;
        int removed = 0;
        for (String rel : touched) {
            try {
                Path shadowFile = shadowManager.resolveInShadow(shadow.root(), rel);
                List<String> newLines = Files.exists(shadowFile)
                        ? Files.readAllLines(shadowFile) : List.of();
                Path baseFile = repoDir.resolve(rel).normalize();
                List<String> oldLines = Files.exists(baseFile)
                        ? Files.readAllLines(baseFile) : List.of();
                int lcs = lcsLength(oldLines, newLines);
                added += newLines.size() - lcs;
                removed += oldLines.size() - lcs;
            } catch (Exception e) {
                log.debug("[solution] 度量文件 {} 失败: {}", rel, e.getMessage());
            }
        }
        return new LineDelta(touched.size(), added, removed);
    }

    /** 经典行级 LCS 长度（文件小，O(n*m) 足够）。用于诚实的增删行统计。 */
    private static int lcsLength(List<String> a, List<String> b) {
        int n = a.size();
        int m = b.size();
        if (n == 0 || m == 0) {
            return 0;
        }
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                if (a.get(i - 1).equals(b.get(j - 1))) {
                    cur[j] = prev[j - 1] + 1;
                } else {
                    cur[j] = Math.max(prev[j], cur[j - 1]);
                }
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[m];
    }

    // ---- 内部：推荐打分 ----

    /**
     * 在 STAGED 分支里按真实指标归一化打分，选最高者推荐（⭐）。
     * 打分只用内核真拿得到的信号：改动面越小越好、净改动行越少越好、token 越省越好、verified 为真加分。
     * 全部并列则取 variant_index 最小者（稳定、可复现）。无可选分支返回 null。
     */
    private Long recommend(List<RkSolutionBranchEntity> branches) {
        List<RkSolutionBranchEntity> candidates = branches.stream()
                .filter(b -> "STAGED".equals(b.getStatus()))
                .toList();
        if (candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.get(0).getId();
        }
        double[] files = candidates.stream().mapToDouble(b -> nz(b.getFilesChanged())).toArray();
        double[] churn = candidates.stream()
                .mapToDouble(b -> nz(b.getLinesAdded()) + nz(b.getLinesRemoved())).toArray();
        double[] tokens = candidates.stream()
                .mapToDouble(b -> b.getTokensSpent() == null ? 0 : b.getTokensSpent()).toArray();

        Long best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < candidates.size(); i++) {
            double score = 0.35 * lowerBetter(files, files[i])
                    + 0.35 * lowerBetter(churn, churn[i])
                    + 0.10 * lowerBetter(tokens, tokens[i])
                    + 0.20 * (Boolean.TRUE.equals(candidates.get(i).getVerified()) ? 1.0 : 0.0);
            if (score > bestScore) {
                bestScore = score;
                best = candidates.get(i).getId();
            }
        }
        return best;
    }

    /** 归一化「越小越好」为 [0,1]：最小值得 1，最大值得 0，全并列得 1。 */
    private static double lowerBetter(double[] all, double v) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (double x : all) {
            min = Math.min(min, x);
            max = Math.max(max, x);
        }
        if (max == min) {
            return 1.0;
        }
        return (max - v) / (max - min);
    }

    // ---- 内部：杂项 ----

    private Path shadowRootOf(RkSolutionBranchEntity branch) {
        return shadowManager.rootOf(branch.getShadowId())
                .orElseThrow(() -> new IllegalStateException("影子区 " + branch.getShadowId() + " 不存在"));
    }

    private List<RkSolutionBranchEntity> loadBranches(Long setId) {
        return branchMapper.selectList(new LambdaQueryWrapper<RkSolutionBranchEntity>()
                .eq(RkSolutionBranchEntity::getSetId, setId)
                .orderByAsc(RkSolutionBranchEntity::getVariantIndex));
    }

    private static String buildBranchPrompt(String question, SolutionStrategy strategy) {
        return question + "\n\n[实现策略：" + strategy.label() + "] " + strategy.hint()
                + "\n请只按这个策略实现，做最小必要改动。";
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
