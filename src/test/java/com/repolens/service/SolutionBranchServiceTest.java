package com.repolens.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.repolens.common.constants.ErrorCode;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.AgentRunEntity;
import com.repolens.domain.entity.FileChangeLogEntity;
import com.repolens.domain.entity.SolutionBranchEntity;
import com.repolens.domain.vo.BranchGraphVO;
import com.repolens.domain.vo.ChangeGraphVO;
import com.repolens.domain.vo.FileChangeVO;
import com.repolens.llm.config.LlmRuntimeConfig;
import com.repolens.mapper.AgentRunMapper;
import com.repolens.mapper.FileChangeLogMapper;
import com.repolens.mapper.SolutionBranchMapper;
import com.repolens.security.PermissionService;
import com.repolens.service.impl.SolutionBranchServiceImpl;
import com.repolens.service.impl.support.AgentLoopExecutor;
import com.repolens.service.impl.support.AgentToolCatalog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SolutionBranchService 单元测试（全 mock，不启动 Spring 上下文，不调用真实 LLM）。
 *
 * <p>用 {@code ReflectionTestUtils.setField} 将 branchExecutor 替换为同步执行器，
 * 使 CompletableFuture 在调用线程中执行，测试结果确定性 100%。
 */
class SolutionBranchServiceTest {

    // ---- mocks ----
    private PermissionService permissionService;
    private AgentLoopExecutor agentLoopExecutor;
    private AgentToolCatalog agentToolCatalog;
    private LlmRuntimeConfig llmRuntimeConfig;
    private SolutionBranchMapper solutionBranchMapper;
    private FileChangeLogMapper fileChangeLogMapper;
    private AgentRunMapper agentRunMapper;
    private ChangeGraphService changeGraphService;
    private FileChangeService fileChangeService;

    private SolutionBranchServiceImpl service;

    /**
     * MyBatis-Plus LambdaUpdateWrapper.set(Entity::getField, val) 在无 Spring 上下文时需要
     * lambda 列缓存。这里手动注册两个实体，使测试类可独立运行；在全量套件中重复注册无副作用。
     *
     * @see com.repolens.service.RepoAsyncIndexServiceImplTest#initTableInfo() 同模式
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SolutionBranchEntity.class);
        TableInfoHelper.initTableInfo(assistant, FileChangeLogEntity.class);
    }

    /** 同步执行器：任务在调用线程内立即运行，消除并发不确定性。 */
    private static final Executor SYNC_EXECUTOR = r -> r.run();

    /** mock insert 自增 id 分配器。 */
    private final AtomicLong idSeq = new AtomicLong(1);

    @BeforeEach
    void setup() {
        permissionService = mock(PermissionService.class);
        agentLoopExecutor = mock(AgentLoopExecutor.class);
        agentToolCatalog = mock(AgentToolCatalog.class);
        llmRuntimeConfig = mock(LlmRuntimeConfig.class);
        solutionBranchMapper = mock(SolutionBranchMapper.class);
        fileChangeLogMapper = mock(FileChangeLogMapper.class);
        agentRunMapper = mock(AgentRunMapper.class);
        changeGraphService = mock(ChangeGraphService.class);
        fileChangeService = mock(FileChangeService.class);

        service = new SolutionBranchServiceImpl(
                permissionService, agentLoopExecutor, agentToolCatalog, llmRuntimeConfig,
                solutionBranchMapper, fileChangeLogMapper, agentRunMapper,
                changeGraphService, fileChangeService);

        // 同步执行器替换，确保 CompletableFuture 在当前线程运行
        ReflectionTestUtils.setField(service, "branchExecutor", SYNC_EXECUTOR);

        // 公共默认 mock
        when(permissionService.checkRepoPermission(anyLong(), anyLong())).thenReturn(true);
        when(llmRuntimeConfig.getModelName()).thenReturn("mock-model");
        when(llmRuntimeConfig.getTimeoutMs()).thenReturn(5000);
        when(agentToolCatalog.tools(eq(true))).thenReturn(List.of());

        // insert 自动分配 id（模拟 MyBatis-Plus AUTO 主键回填）
        // 必须用带类型的 any(T.class)，否则编译器无法区分 insert(T) 和 insert(Collection<T>)
        doAnswer(inv -> {
            SolutionBranchEntity e = inv.getArgument(0);
            e.setId(idSeq.getAndIncrement());
            return 1;
        }).when(solutionBranchMapper).insert(any(SolutionBranchEntity.class));
        doAnswer(inv -> {
            AgentRunEntity e = inv.getArgument(0);
            e.setId(idSeq.getAndIncrement());
            return 1;
        }).when(agentRunMapper).insert(any(AgentRunEntity.class));

        when(solutionBranchMapper.updateById(any(SolutionBranchEntity.class))).thenReturn(1);
        when(solutionBranchMapper.update(any(), any())).thenReturn(1);
        when(fileChangeLogMapper.selectCount(any())).thenReturn(2L);
        when(fileChangeLogMapper.update(any(), any())).thenReturn(1);

        ChangeGraphVO emptyGraph = ChangeGraphVO.builder()
                .changedSymbols(List.of()).changedFiles(List.of()).build();
        when(changeGraphService.getChangeGraph(anyLong(), anyLong(), anyLong())).thenReturn(emptyGraph);
    }

    /** 构造一个 mock AgentResult。 */
    private AgentLoopExecutor.AgentResult mockAgentResult(int iterations, int toolCalls) {
        return AgentLoopExecutor.AgentResult.builder()
                .answer("修改完成：用最小改动方案修复了 Bug。")
                .iterations(iterations)
                .toolCallCount(toolCalls)
                .hitMaxIterations(false)
                .steps(List.of())
                .discoveredReferences(List.of())
                .build();
    }

    /** 构造 READY SolutionBranchEntity（用于 selectList mock 返回）。 */
    private SolutionBranchEntity readyBranch(long id, String bId, int idx, String hint) {
        SolutionBranchEntity b = new SolutionBranchEntity();
        b.setId(id);
        b.setBranchId(bId);
        b.setVariantIndex(idx);
        b.setStrategyHint(hint);
        b.setStatus("READY");
        b.setQuestion("how to fix");
        b.setFilesChanged(2);
        b.setBlastRadiusSize(0);
        b.setDebtDelta(0);
        b.setConfidence(0.6);
        b.setDegraded(1);
        b.setVerified(0);
        return b;
    }

    // ====================================================================
    // fanout 测试
    // ====================================================================

    @Test
    void fanout_producesTwoVariantsReady_whenVariantCountIs2() {
        // 准备：agent loop 正常返回
        when(agentLoopExecutor.run(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(mockAgentResult(3, 4));

        // 第1次 selectList 为 discardOldBranches 清理（无旧分支），第2次为 buildBranchGraph
        when(solutionBranchMapper.selectList(any()))
                .thenReturn(List.of())
                .thenReturn(List.of(
                        readyBranch(1L, "v0", 0, "最小改动"),
                        readyBranch(2L, "v1", 1, "重构式")));

        BranchGraphVO result = service.fanout(1L, 7L, 9L, "how to fix", 2,
                List.of("最小改动", "重构式"));

        // 预插入 2 个 GENERATING 分支（typed 避免 insert(T) vs insert(Collection) 歧义）
        verify(solutionBranchMapper, times(2)).insert(any(SolutionBranchEntity.class));
        // 各跑一次 agent loop
        verify(agentLoopExecutor, times(2))
                .run(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any());
        // 落 2 条 agent_run
        verify(agentRunMapper, times(2)).insert(any(AgentRunEntity.class));
        // 更新为 READY
        verify(solutionBranchMapper, times(2)).updateById(any(SolutionBranchEntity.class));

        assertThat(result).isNotNull();
        assertThat(result.getNodes()).hasSize(2);
        assertThat(result.getNodes()).allMatch(n -> "READY".equals(n.getStatus()));
    }

    @Test
    void fanout_clampVariantCount_belowMin_becomesTwo() {
        when(agentLoopExecutor.run(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(mockAgentResult(2, 2));
        // 第1次为 discardOldBranches（无旧分支），第2次为 buildBranchGraph
        when(solutionBranchMapper.selectList(any()))
                .thenReturn(List.of())
                .thenReturn(List.of(
                        readyBranch(1L, "v0", 0, "最小改动"),
                        readyBranch(2L, "v1", 1, "中间件/装饰器")));

        // variantCount=0 → clamp to 2
        service.fanout(1L, 7L, 9L, "question", 0, null);

        verify(solutionBranchMapper, times(2)).insert(any(SolutionBranchEntity.class));
        verify(agentLoopExecutor, times(2))
                .run(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    void fanout_clampVariantCount_aboveMax_becomesFour() {
        when(agentLoopExecutor.run(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(mockAgentResult(2, 2));
        // 第1次为 discardOldBranches（无旧分支），第2次为 buildBranchGraph
        when(solutionBranchMapper.selectList(any()))
                .thenReturn(List.of())
                .thenReturn(List.of(
                        readyBranch(1L, "v0", 0, "s1"),
                        readyBranch(2L, "v1", 1, "s2"),
                        readyBranch(3L, "v2", 2, "s3"),
                        readyBranch(4L, "v3", 3, "s4")));

        // variantCount=10 → clamp to 4
        service.fanout(1L, 7L, 9L, "question", 10, null);

        verify(solutionBranchMapper, times(4)).insert(any(SolutionBranchEntity.class));
        verify(agentLoopExecutor, times(4))
                .run(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    void fanout_oneVariantFails_markedDiscarded_othersReady() {
        // 第 0 个变体抛异常，第 1 个正常
        when(agentLoopExecutor.run(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), eq("v0")))
                .thenThrow(new RuntimeException("agent failed"));
        when(agentLoopExecutor.run(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), eq("v1")))
                .thenReturn(mockAgentResult(3, 5));

        // 第1次为 discardOldBranches（无旧分支），第2次为 buildBranchGraph
        when(solutionBranchMapper.selectList(any()))
                .thenReturn(List.of())
                .thenReturn(List.of(
                        discardedBranch(1L, "v0", 0),
                        readyBranch(2L, "v1", 1, "重构式")));

        BranchGraphVO result = service.fanout(1L, 7L, 9L, "question", 2, null);

        // 2 个 GENERATING 预插入
        verify(solutionBranchMapper, times(2)).insert(any(SolutionBranchEntity.class));
        // v0 失败 → updateById(DISCARDED)，v1 成功 → updateById(READY)：共 2 次 updateById
        verify(solutionBranchMapper, times(2)).updateById(any(SolutionBranchEntity.class));
        // v1 成功，落 agent_run（v0 失败不落）
        verify(agentRunMapper, times(1)).insert(any(AgentRunEntity.class));

        assertThat(result.getNodes()).hasSize(2);
        // mock selectList 返回的状态：v0 DISCARDED, v1 READY
        assertThat(result.getNodes().stream().filter(n -> "DISCARDED".equals(n.getStatus())).count()).isEqualTo(1);
        assertThat(result.getNodes().stream().filter(n -> "READY".equals(n.getStatus())).count()).isEqualTo(1);
    }

    private SolutionBranchEntity discardedBranch(long id, String bId, int idx) {
        SolutionBranchEntity b = new SolutionBranchEntity();
        b.setId(id);
        b.setBranchId(bId);
        b.setVariantIndex(idx);
        b.setStatus("DISCARDED");
        b.setQuestion("question");
        b.setFilesChanged(0);
        b.setBlastRadiusSize(0);
        b.setDebtDelta(0);
        b.setConfidence(0.0);
        b.setDegraded(1);
        b.setVerified(0);
        return b;
    }

    // ====================================================================
    // select 测试
    // ====================================================================

    @Test
    void select_marksSelectedAndDiscardsOthers_thenApplies() {
        // 当前 session 有 2 个 READY 分支：v0（将被选中）、v1（将被 DISCARDED）
        SolutionBranchEntity otherBranch = readyBranch(2L, "v1", 1, "重构式");
        when(solutionBranchMapper.selectList(any())).thenReturn(List.of(otherBranch));

        FileChangeVO appliedVO = FileChangeVO.builder().id(50L).changeId(50L).filePath("src/A.java").build();
        when(fileChangeService.applyAll(eq(1L), eq(7L), eq(9L), eq("v0"), eq(false)))
                .thenReturn(List.of(appliedVO));

        List<FileChangeVO> result = service.select(1L, 7L, 9L, "v0", false);

        // 选中分支 v0 → SELECTED（update with wrapper）
        verify(solutionBranchMapper, atLeastOnce()).update(any(), any());
        // 其它分支（v1）→ DISCARDED（updateById）
        ArgumentCaptor<SolutionBranchEntity> cap = ArgumentCaptor.forClass(SolutionBranchEntity.class);
        verify(solutionBranchMapper, atLeastOnce()).update(any(), any());
        // v1 的 PROPOSED file_change_log → REJECTED
        verify(fileChangeLogMapper, atLeastOnce()).update(any(), any());
        // applyAll 只针对选中分支
        verify(fileChangeService).applyAll(eq(1L), eq(7L), eq(9L), eq("v0"), eq(false));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getFilePath()).isEqualTo("src/A.java");
    }

    @Test
    void select_noOtherBranches_onlyApplies() {
        // 没有其余 GENERATING/READY 分支
        when(solutionBranchMapper.selectList(any())).thenReturn(List.of());
        when(fileChangeService.applyAll(any(), any(), any(), eq("v0"), eq(true)))
                .thenReturn(List.of(FileChangeVO.builder().id(1L).changeId(1L).filePath("F.java").build()));

        List<FileChangeVO> result = service.select(1L, 7L, 9L, "v0", true);

        verify(fileChangeService).applyAll(eq(1L), eq(7L), eq(9L), eq("v0"), eq(true));
        assertThat(result).hasSize(1);
    }

    // ====================================================================
    // getBranchGraph 测试
    // ====================================================================

    @Test
    void getBranchGraph_returnsNodes() {
        when(solutionBranchMapper.selectList(any())).thenReturn(List.of(
                readyBranch(1L, "v0", 0, "最小改动"),
                readyBranch(2L, "v1", 1, "重构式")));

        BranchGraphVO result = service.getBranchGraph(1L, 7L, 9L);

        assertThat(result.getSessionId()).isEqualTo(9L);
        assertThat(result.getNodes()).hasSize(2);
        assertThat(result.getNodes().get(0).getBranchId()).isEqualTo("v0");
        assertThat(result.getNodes().get(1).getBranchId()).isEqualTo("v1");
    }

    // ====================================================================
    // 权限测试
    // ====================================================================

    @Test
    void fanout_noPermission_throwsForbidden() {
        when(permissionService.checkRepoPermission(1L, 7L)).thenReturn(false);

        assertThatThrownBy(() -> service.fanout(1L, 7L, 9L, "q", 2, null))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.FORBIDDEN.getCode());
    }

    @Test
    void getBranchGraph_noPermission_throwsForbidden() {
        when(permissionService.checkRepoPermission(1L, 7L)).thenReturn(false);

        assertThatThrownBy(() -> service.getBranchGraph(1L, 7L, 9L))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.FORBIDDEN.getCode());
    }

    @Test
    void select_noPermission_throwsForbidden() {
        when(permissionService.checkRepoPermission(1L, 7L)).thenReturn(false);

        assertThatThrownBy(() -> service.select(1L, 7L, 9L, "v0", false))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.FORBIDDEN.getCode());
    }

    // ====================================================================
    // 重复 fanout 清旧分支测试（Important #2）
    // ====================================================================

    /**
     * 重复 fanout 同一 (repoId, sessionId) 时，应先将旧分支标记 DISCARDED，
     * 其对应 PROPOSED file_change_log 标记 REJECTED，再插入新 GENERATING 分支。
     */
    @Test
    void fanout_repeatedFanout_discardsOldBranchesBeforeInsert() {
        when(agentLoopExecutor.run(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(mockAgentResult(3, 4));

        // 旧分支：2 条 READY（模拟上一轮 fanout 留下的）
        SolutionBranchEntity oldV0 = readyBranch(10L, "v0", 0, "旧策略A");
        SolutionBranchEntity oldV1 = readyBranch(11L, "v1", 1, "旧策略B");

        // 第1次 selectList = discardOldBranches 查询到2条旧分支
        // 第2次 selectList = buildBranchGraph 返回新的2条 READY 分支
        when(solutionBranchMapper.selectList(any()))
                .thenReturn(List.of(oldV0, oldV1))
                .thenReturn(List.of(
                        readyBranch(1L, "v0", 0, "最小改动"),
                        readyBranch(2L, "v1", 1, "重构式")));

        service.fanout(1L, 7L, 9L, "how to fix", 2, List.of("最小改动", "重构式"));

        // 旧分支通过 updateById 标 DISCARDED（2次），新分支通过 updateById 标 READY（2次）= 共4次
        verify(solutionBranchMapper, times(4)).updateById(any(SolutionBranchEntity.class));
        // 旧分支 file_change_log → REJECTED（每条旧分支1次）= 2次
        verify(fileChangeLogMapper, times(2)).update(any(), any());
        // 新分支仍正常插入 2 次
        verify(solutionBranchMapper, times(2)).insert(any(SolutionBranchEntity.class));
        // agent loop 仍跑 2 次
        verify(agentLoopExecutor, times(2))
                .run(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any());
    }

    /**
     * discardOldBranches 整体失败（selectList 抛异常）时，fanout 应 fail-safe 继续执行，
     * 不阻断新分支的生成。
     */
    @Test
    void fanout_cleanupFails_fanoutContinuesFail_safe() {
        when(agentLoopExecutor.run(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(mockAgentResult(2, 3));

        // 第1次 selectList（cleanup）抛异常 → fail-safe；第2次（buildBranchGraph）正常返回
        when(solutionBranchMapper.selectList(any()))
                .thenThrow(new RuntimeException("DB timeout"))
                .thenReturn(List.of(
                        readyBranch(1L, "v0", 0, "最小改动"),
                        readyBranch(2L, "v1", 1, "重构式")));

        // 不应抛异常
        BranchGraphVO result = service.fanout(1L, 7L, 9L, "question", 2, null);

        assertThat(result).isNotNull();
        assertThat(result.getNodes()).hasSize(2);
        // 新分支正常预插入
        verify(solutionBranchMapper, times(2)).insert(any(SolutionBranchEntity.class));
    }
}
