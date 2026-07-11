package com.repolens.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.AgentRunEntity;
import com.repolens.domain.entity.FileChangeLogEntity;
import com.repolens.domain.entity.SolutionBranchEntity;
import com.repolens.domain.vo.BranchGraphVO;
import com.repolens.domain.vo.BranchMetricsVO;
import com.repolens.domain.vo.BranchNodeVO;
import com.repolens.domain.vo.ChangeGraphVO;
import com.repolens.domain.vo.FileChangeVO;
import com.repolens.llm.config.LlmRuntimeConfig;
import com.repolens.llm.model.LlmMessage;
import com.repolens.llm.model.ToolDefinition;
import com.repolens.mapper.AgentRunMapper;
import com.repolens.mapper.FileChangeLogMapper;
import com.repolens.mapper.SolutionBranchMapper;
import com.repolens.security.PermissionService;
import com.repolens.service.ChangeGraphService;
import com.repolens.service.FileChangeService;
import com.repolens.service.SolutionBranchService;
import com.repolens.service.impl.support.AgentLoopExecutor;
import com.repolens.service.impl.support.AgentToolCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * K方案分支图服务实现。
 *
 * <p><b>fanout 并发编排</b>：为每个变体预先写入 GENERATING 行，
 * 随后以 CompletableFuture 并发跑各自的 agent loop；单变体失败 → DISCARDED，
 * 绝不影响其它变体，也绝不影响现有问答主链路。
 *
 * <p><b>seedMessages 自组策略</b>：在 SolutionBranchServiceImpl 内自行构造
 * system + user 两条消息，注入 strategy_hint 文本以区分变体行为；不侵入
 * CodeAnswerServiceImpl，解耦更彻底。由于 AgentLoopExecutor.run() 签名不含
 * temperature 参数（内部固定 0.2），变体差异化完全依赖 strategy_hint 文本。
 *
 * <p><b>metrics 计算</b>：
 * <ul>
 *   <li>filesChanged = file_change_log 中该 branchId PROPOSED 行数</li>
 *   <li>blastRadiusSize = ChangeGraphService.getChangeGraph().changedSymbols.size()（失败安全→0）</li>
 *   <li>debtDelta = 0（P1 简化）</li>
 *   <li>confidence = 启发式：min(0.95, toolCallCount×0.1 + iterations×0.05 + 0.4)</li>
 *   <li>degraded = 1 / verified = 0（P1 恒定）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SolutionBranchServiceImpl implements SolutionBranchService {

    /** code 模式最大迭代轮数（与 CodeAnswerServiceImpl 保持一致）。 */
    private static final int CODE_MAX_ITERATIONS = 8;

    /** approach 存储最大字符数。 */
    private static final int APPROACH_MAX_CHARS = 512;

    /** 默认策略列表；strategies 为空时取前 variantCount 条。 */
    private static final List<String> DEFAULT_STRATEGIES =
            List.of("最小改动", "中间件/装饰器", "重构式", "测试驱动");

    private static final AtomicInteger BRANCH_THREAD_COUNTER = new AtomicInteger();

    /**
     * 分支并发执行器，package-private 以便测试用 ReflectionTestUtils.setField 替换为同步执行器。
     * CallerRunsPolicy 保证队列满时退化为调用方线程执行（fail-safe）。
     */
    Executor branchExecutor = new ThreadPoolExecutor(
            2, 4,
            60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8),
            r -> {
                Thread t = new Thread(r, "branch-fanout-" + BRANCH_THREAD_COUNTER.incrementAndGet());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());

    private final PermissionService permissionService;
    private final AgentLoopExecutor agentLoopExecutor;
    private final AgentToolCatalog agentToolCatalog;
    private final LlmRuntimeConfig llmRuntimeConfig;
    private final SolutionBranchMapper solutionBranchMapper;
    private final FileChangeLogMapper fileChangeLogMapper;
    private final AgentRunMapper agentRunMapper;
    private final ChangeGraphService changeGraphService;
    private final FileChangeService fileChangeService;

    // ========================= 公共 API ========================= //

    @Override
    public BranchGraphVO fanout(Long userId, Long repoId, Long sessionId, String question,
                                int variantCount, List<String> strategies) {
        checkPermission(userId, repoId);

        // clamp [2, 4]
        int count = Math.min(4, Math.max(2, variantCount));

        List<String> effectiveStrategies = (strategies != null && !strategies.isEmpty())
                ? strategies : DEFAULT_STRATEGIES;

        // 0. 清理该 (repoId, sessionId) 下的旧分支，防止重复 fanout 产生重复 branchId
        discardOldBranches(repoId, sessionId);

        // 1. 预写 GENERATING 分支，获取主键供后续异步更新
        List<SolutionBranchEntity> branches = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String bId = "v" + i;
            String hint = i < effectiveStrategies.size() ? effectiveStrategies.get(i) : "综合方案";
            SolutionBranchEntity branch = new SolutionBranchEntity();
            branch.setRepoId(repoId);
            branch.setSessionId(sessionId);
            branch.setBranchId(bId);
            branch.setVariantIndex(i);
            branch.setLabel("方案 " + (i + 1) + "（" + hint + "）");
            branch.setStrategyHint(hint);
            branch.setStatus("GENERATING");
            branch.setQuestion(question);
            branch.setDegraded(1);
            branch.setVerified(0);
            branch.setFilesChanged(0);
            branch.setBlastRadiusSize(0);
            branch.setDebtDelta(0);
            branch.setConfidence(0.0);
            branch.setCreatedAt(LocalDateTime.now());
            branch.setUpdatedAt(LocalDateTime.now());
            solutionBranchMapper.insert(branch);
            branches.add(branch);
        }

        // 2. 并发跑各变体
        List<CompletableFuture<Void>> futures = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final SolutionBranchEntity branch = branches.get(i);
            final String bId = branch.getBranchId();
            final String hint = branch.getStrategyHint();
            final int idx = i;

            CompletableFuture<Void> f = CompletableFuture.supplyAsync(() -> {
                try {
                    runVariant(userId, repoId, sessionId, question, idx, bId, hint, branch.getId());
                } catch (Exception ex) {
                    // 单变体完全失败兜底（runVariant 内部已有 try-catch，此处双保险）
                    log.warn("branch variant top-level fail, branchId={}, err={}", bId, ex.getMessage());
                    markDiscarded(branch.getId(), bId);
                }
                return null;
            }, branchExecutor);

            futures.add(f);
        }

        // 3. 等待全部完成（join 不抛 checked，CompletionException 会在此传播；
        //    内部已全 catch，不会抛到这里）
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // 4. 返回最终分支图
        return buildBranchGraph(repoId, sessionId, question);
    }

    @Override
    public BranchGraphVO getBranchGraph(Long userId, Long repoId, Long sessionId) {
        checkPermission(userId, repoId);
        // 单次 selectList 即可，question 从首条 branch 取（buildBranchGraph 内处理 null）
        return buildBranchGraph(repoId, sessionId, null);
    }

    @Override
    public List<FileChangeVO> select(Long userId, Long repoId, Long sessionId,
                                     String branchId, boolean ack) {
        checkPermission(userId, repoId);

        // 1. 选中分支 → SELECTED
        solutionBranchMapper.update(null,
                Wrappers.<SolutionBranchEntity>lambdaUpdate()
                        .set(SolutionBranchEntity::getStatus, "SELECTED")
                        .set(SolutionBranchEntity::getUpdatedAt, LocalDateTime.now())
                        .eq(SolutionBranchEntity::getRepoId, repoId)
                        .eq(SolutionBranchEntity::getSessionId, sessionId)
                        .eq(SolutionBranchEntity::getBranchId, branchId));

        // 2. 其余 GENERATING/READY 分支 → DISCARDED
        List<SolutionBranchEntity> others = solutionBranchMapper.selectList(
                Wrappers.<SolutionBranchEntity>lambdaQuery()
                        .eq(SolutionBranchEntity::getRepoId, repoId)
                        .eq(SolutionBranchEntity::getSessionId, sessionId)
                        .ne(SolutionBranchEntity::getBranchId, branchId)
                        .in(SolutionBranchEntity::getStatus, "GENERATING", "READY"));

        for (SolutionBranchEntity other : others) {
            solutionBranchMapper.update(null,
                    Wrappers.<SolutionBranchEntity>lambdaUpdate()
                            .set(SolutionBranchEntity::getStatus, "DISCARDED")
                            .set(SolutionBranchEntity::getUpdatedAt, LocalDateTime.now())
                            .eq(SolutionBranchEntity::getId, other.getId()));

            // 其 PROPOSED file_change_log → REJECTED（不落盘，仅 DB 状态标记）
            try {
                fileChangeLogMapper.update(null,
                        Wrappers.<FileChangeLogEntity>lambdaUpdate()
                                .set(FileChangeLogEntity::getStatus, FileChangeLogEntity.STATUS_REJECTED)
                                .eq(FileChangeLogEntity::getRepoId, repoId)
                                .eq(FileChangeLogEntity::getSessionId, sessionId)
                                .eq(FileChangeLogEntity::getBranchId, other.getBranchId())
                                .eq(FileChangeLogEntity::getStatus, FileChangeLogEntity.STATUS_PROPOSED));
            } catch (Exception ex) {
                log.warn("reject discarded branch file_change_log failed, branchId={}, err={}",
                        other.getBranchId(), ex.getMessage());
            }
        }

        // 3. apply 选中分支的 PROPOSED 变更（写盘）
        return fileChangeService.applyAll(userId, repoId, sessionId, branchId, ack);
    }

    // ========================= 私有辅助 ========================= //

    /** 权限校验：无权限抛 BizException(FORBIDDEN)。 */
    private void checkPermission(Long userId, Long repoId) {
        if (!permissionService.checkRepoPermission(userId, repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "No permission for repo " + repoId);
        }
    }

    /**
     * 跑单个变体的 agent loop 并更新数据库记录（fail-safe 内核）。
     * 任何异常都在此 catch 并将该分支标记为 DISCARDED。
     */
    private void runVariant(Long userId, Long repoId, Long sessionId, String question,
                            int idx, String bId, String hint, Long branchRowId) {
        try {
            // 构造 seedMessages（system + strategy_hint 注入 + user = question）
            List<LlmMessage> seedMessages = buildSeedMessages(question, hint);
            List<ToolDefinition> tools = agentToolCatalog.tools(true); // codeMode=true
            String modelName = llmRuntimeConfig.getModelName();
            int timeoutMs = llmRuntimeConfig.getTimeoutMs();

            AgentLoopExecutor.AgentResult agentResult = agentLoopExecutor.run(
                    userId, repoId, sessionId, seedMessages, tools,
                    modelName, CODE_MAX_ITERATIONS, timeoutMs, bId);

            // 落 agent_run（带 branchId/variantIndex，直接 mapper 插入以设置 K 方案扩展字段）
            Long agentRunId = recordAgentRun(userId, repoId, sessionId, question, idx, bId, agentResult);

            // 计算 metrics（各自 fail-safe）
            int filesChanged = countProposedFiles(repoId, sessionId, bId);
            int blastRadius = computeBlastRadius(userId, repoId, agentRunId);
            double confidence = computeConfidence(agentResult);

            // 更新 solution_branch → READY
            SolutionBranchEntity upd = new SolutionBranchEntity();
            upd.setId(branchRowId);
            upd.setStatus("READY");
            upd.setAgentRunId(agentRunId);
            upd.setApproach(truncate(agentResult.getAnswer(), APPROACH_MAX_CHARS));
            upd.setFilesChanged(filesChanged);
            upd.setBlastRadiusSize(blastRadius);
            upd.setDebtDelta(0);
            upd.setConfidence(confidence);
            upd.setDegraded(1);
            upd.setVerified(0);
            upd.setUpdatedAt(LocalDateTime.now());
            solutionBranchMapper.updateById(upd);

            log.info("branch variant READY, branchId={}, repoId={}, filesChanged={}, blastRadius={}",
                    bId, repoId, filesChanged, blastRadius);

        } catch (Exception ex) {
            log.warn("branch variant failed, branchId={}, repoId={}, err={}", bId, repoId, ex.getMessage());
            markDiscarded(branchRowId, bId);
        }
    }

    /** 将指定分支标记为 DISCARDED（fail-safe 自身不抛）。 */
    private void markDiscarded(Long branchRowId, String bId) {
        try {
            SolutionBranchEntity upd = new SolutionBranchEntity();
            upd.setId(branchRowId);
            upd.setStatus("DISCARDED");
            upd.setUpdatedAt(LocalDateTime.now());
            solutionBranchMapper.updateById(upd);
        } catch (Exception e2) {
            log.warn("failed to mark branch DISCARDED, branchId={}", bId);
        }
    }

    /**
     * fanout 开始前清理该 (repoId, sessionId) 的旧分支，防止重复 fanout 产生重复 branchId
     * 导致 select/graph 命中多行或混入上轮数据。
     *
     * <p>策略：旧 solution_branch → DISCARDED；其下 PROPOSED file_change_log → REJECTED。
     * fail-safe：整体异常只 log，不阻塞新一轮 fanout；单条更新失败也继续处理剩余行。
     */
    private void discardOldBranches(Long repoId, Long sessionId) {
        try {
            List<SolutionBranchEntity> existing = solutionBranchMapper.selectList(
                    Wrappers.<SolutionBranchEntity>lambdaQuery()
                            .eq(SolutionBranchEntity::getRepoId, repoId)
                            .eq(SolutionBranchEntity::getSessionId, sessionId));

            if (existing.isEmpty()) {
                return;
            }

            for (SolutionBranchEntity old : existing) {
                // 旧分支 → DISCARDED
                try {
                    SolutionBranchEntity upd = new SolutionBranchEntity();
                    upd.setId(old.getId());
                    upd.setStatus("DISCARDED");
                    upd.setUpdatedAt(LocalDateTime.now());
                    solutionBranchMapper.updateById(upd);
                } catch (Exception ex) {
                    log.warn("discardOldBranches: updateById failed, branchId={}, err={}",
                            old.getBranchId(), ex.getMessage());
                }

                // 旧分支的 PROPOSED file_change_log → REJECTED
                if (old.getBranchId() != null) {
                    try {
                        fileChangeLogMapper.update(null,
                                Wrappers.<FileChangeLogEntity>lambdaUpdate()
                                        .set(FileChangeLogEntity::getStatus, FileChangeLogEntity.STATUS_REJECTED)
                                        .eq(FileChangeLogEntity::getRepoId, repoId)
                                        .eq(FileChangeLogEntity::getSessionId, sessionId)
                                        .eq(FileChangeLogEntity::getBranchId, old.getBranchId())
                                        .eq(FileChangeLogEntity::getStatus, FileChangeLogEntity.STATUS_PROPOSED));
                    } catch (Exception ex) {
                        log.warn("discardOldBranches: reject file_change_log failed, branchId={}, err={}",
                                old.getBranchId(), ex.getMessage());
                    }
                }
            }

            log.info("discardOldBranches: cleared {} old branches for repoId={}, sessionId={}",
                    existing.size(), repoId, sessionId);
        } catch (Exception ex) {
            log.warn("discardOldBranches: cleanup aborted (fail-safe), repoId={}, sessionId={}, err={}",
                    repoId, sessionId, ex.getMessage());
        }
    }

    /**
     * 构造 agent loop 的种子消息（system + user）。
     *
     * <p>system prompt 包含编码模式指引与 strategy_hint 注入；
     * AgentLoopExecutor.run() 内部 temperature 固定 0.2，因此变体差异化完全依赖 strategy_hint 文本区分。
     */
    private List<LlmMessage> buildSeedMessages(String question, String strategyHint) {
        String systemPrompt =
                "你是 RepoLens 代码改造助手，专注于针对给定问题实现具体的代码修改方案。\n"
                + "你处于编码模式：优先用 editFileContent 做最小 str_replace 修改；"
                + "需要整文件重写才用 writeFileContent；新建文件用 createFileContent。"
                + "务必先 getFileContent 读取原文再修改；"
                + "改完在回答里清楚总结你改了哪些文件、改了什么。\n"
                + "修改代码后建议调用 runVerification 验证编译（kind=\"build\"）或测试（kind=\"test\"）；"
                + "若失败，阅读 outputTail 错误信息并用 editFileContent 修正后再次验证。\n\n"
                + "## 当前方案策略（Strategy Hint）\n"
                + strategyHint + "\n"
                + "请严格按照以上策略风格实现解决方案，不要偏离策略方向。";
        return List.of(
                LlmMessage.builder().role("system").content(systemPrompt).build(),
                LlmMessage.builder().role("user").content(question).build());
    }

    /**
     * 直接 insert agent_run，支持 K 方案扩展字段 branchId/variantIndex。
     * AgentRunService.record() 目前不含这两个字段，故此处自组。
     * 失败安全：异常时返回 null（agentRunId=null 对后续 metrics 无影响）。
     */
    private Long recordAgentRun(Long userId, Long repoId, Long sessionId, String question,
                                int idx, String bId, AgentLoopExecutor.AgentResult agentResult) {
        try {
            AgentRunEntity run = new AgentRunEntity();
            run.setRepoId(repoId);
            run.setSessionId(sessionId);
            run.setUserId(userId);
            run.setQuestion(truncate(question, 2000));
            run.setMode("code");
            run.setAnswerPreview(truncate(agentResult.getAnswer(), 500));
            run.setIterations(agentResult.getIterations());
            run.setToolCalls(agentResult.getToolCallCount());
            run.setStatus("DONE");
            run.setBranchId(bId);
            run.setVariantIndex(idx);
            run.setCreatedAt(LocalDateTime.now());
            agentRunMapper.insert(run);
            return run.getId();
        } catch (Exception ex) {
            log.warn("recordAgentRun failed (fail-safe), branchId={}, err={}", bId, ex.getMessage());
            return null;
        }
    }

    /** 统计该 (repoId, branchId) 下 PROPOSED 的 file_change_log 行数（fail-safe）。 */
    private int countProposedFiles(Long repoId, Long sessionId, String bId) {
        try {
            Long cnt = fileChangeLogMapper.selectCount(
                    Wrappers.<FileChangeLogEntity>lambdaQuery()
                            .eq(FileChangeLogEntity::getRepoId, repoId)
                            .eq(FileChangeLogEntity::getSessionId, sessionId)
                            .eq(FileChangeLogEntity::getBranchId, bId)
                            .eq(FileChangeLogEntity::getStatus, FileChangeLogEntity.STATUS_PROPOSED));
            return cnt == null ? 0 : cnt.intValue();
        } catch (Exception ex) {
            log.warn("countProposedFiles failed (fail-safe), branchId={}, err={}", bId, ex.getMessage());
            return 0;
        }
    }

    /**
     * 通过 ChangeGraphService 计算爆炸半径节点数（fail-safe）。
     * agentRunId 为 null 时直接返回 0。
     * changedSymbols 是变更涉及的代码符号（类/方法），其 size 即为影响面节点数。
     */
    private int computeBlastRadius(Long userId, Long repoId, Long agentRunId) {
        if (agentRunId == null) {
            return 0;
        }
        try {
            ChangeGraphVO graph = changeGraphService.getChangeGraph(userId, repoId, agentRunId);
            if (graph == null || graph.getChangedSymbols() == null) {
                return 0;
            }
            return graph.getChangedSymbols().size();
        } catch (Exception ex) {
            log.warn("computeBlastRadius failed (fail-safe), agentRunId={}, err={}", agentRunId, ex.getMessage());
            return 0;
        }
    }

    /**
     * 启发式置信度：基于 iterations 和 toolCalls 线性组合，上限 0.95。
     * degraded=1 表示这只是静态自评，不是真实验证。
     */
    private double computeConfidence(AgentLoopExecutor.AgentResult agentResult) {
        double base = 0.40;
        double fromToolCalls = Math.min(agentResult.getToolCallCount() * 0.08, 0.30);
        double fromIterations = Math.min(agentResult.getIterations() * 0.05, 0.25);
        double raw = base + fromToolCalls + fromIterations;
        // 若达到迭代上限（hitMaxIterations），说明 agent 没有干净收敛，适当惩罚
        if (agentResult.isHitMaxIterations()) {
            raw *= 0.75;
        }
        return Math.min(0.95, raw);
    }

    /** 构造分支图 VO（不含权限校验，供内部复用）。repoId 作用域隔离，防止跨 repo 信息泄露。 */
    private BranchGraphVO buildBranchGraph(Long repoId, Long sessionId, String questionHint) {
        List<SolutionBranchEntity> branches = solutionBranchMapper.selectList(
                Wrappers.<SolutionBranchEntity>lambdaQuery()
                        .eq(SolutionBranchEntity::getRepoId, repoId)
                        .eq(SolutionBranchEntity::getSessionId, sessionId)
                        .orderByAsc(SolutionBranchEntity::getVariantIndex));

        String question = questionHint;
        if (question == null && !branches.isEmpty()) {
            question = branches.get(0).getQuestion();
        }

        List<BranchNodeVO> nodes = branches.stream().map(b -> {
            BranchNodeVO node = new BranchNodeVO();
            node.setId(b.getId());
            node.setBranchId(b.getBranchId());
            node.setVariantIndex(b.getVariantIndex() != null ? b.getVariantIndex() : 0);
            node.setParentBranchId(b.getParentBranchId());
            node.setAgentRunId(b.getAgentRunId());
            node.setLabel(b.getLabel());
            node.setApproach(b.getApproach());
            node.setStrategyHint(b.getStrategyHint());
            node.setStatus(b.getStatus());
            node.setDegraded(b.getDegraded() != null && b.getDegraded() == 1);

            BranchMetricsVO metrics = new BranchMetricsVO();
            metrics.setFilesChanged(b.getFilesChanged() != null ? b.getFilesChanged() : 0);
            metrics.setBlastRadiusSize(b.getBlastRadiusSize() != null ? b.getBlastRadiusSize() : 0);
            metrics.setDebtDelta(b.getDebtDelta() != null ? b.getDebtDelta() : 0);
            metrics.setConfidence(b.getConfidence());
            metrics.setVerified(b.getVerified() != null && b.getVerified() == 1);
            node.setMetrics(metrics);
            return node;
        }).collect(Collectors.toList());

        BranchGraphVO vo = new BranchGraphVO();
        vo.setSessionId(sessionId);
        vo.setQuestion(question);
        vo.setNodes(nodes);
        return vo;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
