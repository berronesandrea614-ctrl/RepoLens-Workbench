package com.repolens.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.AgentRunEntity;
import com.repolens.domain.entity.CodeFileEntity;
import com.repolens.domain.entity.CodeSymbolEntity;
import com.repolens.domain.entity.FileChangeLogEntity;
import com.repolens.domain.enums.SymbolType;
import com.repolens.domain.vo.CodeGraphVO;
import com.repolens.domain.vo.FrameVO;
import com.repolens.domain.vo.GraphNodeVO;
import com.repolens.domain.vo.TimelineVO;
import com.repolens.mapper.AgentRunMapper;
import com.repolens.mapper.CodeDependencyMapper;
import com.repolens.mapper.CodeFileMapper;
import com.repolens.mapper.CodeSymbolMapper;
import com.repolens.mapper.FileChangeLogMapper;
import com.repolens.security.PermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ArchTimelineServiceImpl 单测（mock 所有 mapper + PermissionService，无真实 DB）。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>getTimeline 3 run → 3 帧，按 createdAt 升序，每帧 changedFilePaths 正确</li>
 *   <li>getTimeline 空 agent_run → 空 timeline（historyLimited=true）</li>
 *   <li>getTimeline 无权限 → FORBIDDEN</li>
 *   <li>getFrameGraph 累积 firstSeenFrame 正确</li>
 *   <li>getFrameGraph changeType: 本帧首见 NEW / 非首见 MODIFIED / 历史触碰本帧未碰 STABLE</li>
 *   <li>getFrameGraph touchCount 计数正确</li>
 *   <li>getFrameGraph frameIndex 越界 → NOT_FOUND</li>
 *   <li>getFrameGraph 无权限 → FORBIDDEN</li>
 * </ul>
 */
class ArchTimelineServiceImplTest {

    private static final Long USER_ID = 1L;
    private static final Long REPO_ID = 10L;

    private PermissionService permissionService;
    private AgentRunMapper agentRunMapper;
    private FileChangeLogMapper fileChangeLogMapper;
    private CodeFileMapper codeFileMapper;
    private CodeSymbolMapper codeSymbolMapper;
    private CodeDependencyMapper codeDependencyMapper;
    private ArchTimelineServiceImpl service;

    @BeforeEach
    void setUp() {
        permissionService = mock(PermissionService.class);
        agentRunMapper = mock(AgentRunMapper.class);
        fileChangeLogMapper = mock(FileChangeLogMapper.class);
        codeFileMapper = mock(CodeFileMapper.class);
        codeSymbolMapper = mock(CodeSymbolMapper.class);
        codeDependencyMapper = mock(CodeDependencyMapper.class);

        service = new ArchTimelineServiceImpl(
                agentRunMapper, fileChangeLogMapper, codeFileMapper,
                codeSymbolMapper, codeDependencyMapper, permissionService);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // getTimeline 场景
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void getTimeline_throwsForbiddenWhenNoPermission() {
        when(permissionService.checkRepoPermission(USER_ID, REPO_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.getTimeline(USER_ID, REPO_ID))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.FORBIDDEN.getCode());

        verify(agentRunMapper, never()).selectList(any());
    }

    @Test
    void getTimeline_emptyAgentRuns_returnsEmptyTimeline() {
        when(permissionService.checkRepoPermission(USER_ID, REPO_ID)).thenReturn(true);
        when(agentRunMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());

        TimelineVO result = service.getTimeline(USER_ID, REPO_ID);

        assertThat(result.getFrames()).isEmpty();
        assertThat(result.getFrameCount()).isEqualTo(0);
        assertThat(result.isHistoryLimited()).isTrue();
    }

    @Test
    void getTimeline_3Runs_returns3FramesWithCorrectFilePaths() {
        when(permissionService.checkRepoPermission(USER_ID, REPO_ID)).thenReturn(true);

        // 3 agent_run, ordered by createdAt ASC (mapper already ordered)
        AgentRunEntity run0 = makeRun(100L, REPO_ID, 1L, LocalDateTime.of(2026, 1, 1, 0, 0));
        AgentRunEntity run1 = makeRun(101L, REPO_ID, 2L, LocalDateTime.of(2026, 1, 2, 0, 0));
        AgentRunEntity run2 = makeRun(102L, REPO_ID, 3L, LocalDateTime.of(2026, 1, 3, 0, 0));
        when(agentRunMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(run0, run1, run2));

        // code_file mapping
        CodeFileEntity file1 = makeCodeFile(10L, REPO_ID, "src/Foo.java");
        CodeFileEntity file2 = makeCodeFile(11L, REPO_ID, "src/Bar.java");
        when(codeFileMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(file1, file2));

        // file_change_log per run
        // run0 -> Foo.java (APPLIED)
        FileChangeLogEntity log0 = makeChangeLog(50L, REPO_ID, 100L, "src/Foo.java", "APPLIED");
        // run1 -> Bar.java (PROPOSED)
        FileChangeLogEntity log1 = makeChangeLog(51L, REPO_ID, 101L, "src/Bar.java", "PROPOSED");
        // run2 -> Foo.java + Bar.java (APPLIED)
        FileChangeLogEntity log2a = makeChangeLog(52L, REPO_ID, 102L, "src/Foo.java", "APPLIED");
        FileChangeLogEntity log2b = makeChangeLog(53L, REPO_ID, 102L, "src/Bar.java", "APPLIED");

        when(fileChangeLogMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenAnswer(inv -> {
                    LambdaQueryWrapper<FileChangeLogEntity> wrapper = inv.getArgument(0);
                    // We use the agentRunId from the wrapper to distinguish calls
                    // Since we can't easily inspect wrapper, use thenAnswer with call count tracking
                    return Collections.emptyList(); // fallback
                });

        // Better approach: use specific stubs per agentRunId via argument matching on wrapper
        // Since LambdaQueryWrapper is hard to inspect in mock, we stub selectList call-order style
        // Calls: 1=codeFileMapper, then fileChangeLogMapper x3 (run0,run1,run2), codeSymbolMapper x3
        // Use answer that checks which invocation it is
        final int[] fcallCount = {0};
        when(fileChangeLogMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenAnswer(inv -> {
                    int call = fcallCount[0]++;
                    if (call == 0) return List.of(log0);
                    if (call == 1) return List.of(log1);
                    if (call == 2) return List.of(log2a, log2b);
                    return Collections.emptyList();
                });

        // code_symbol count: fileId=10 (Foo) has 2 symbols, fileId=11 (Bar) has 3 symbols
        when(codeSymbolMapper.selectCount(any(LambdaQueryWrapper.class)))
                .thenReturn(2L, 3L, 5L); // run0:2, run1:3, run2:5

        TimelineVO result = service.getTimeline(USER_ID, REPO_ID);

        assertThat(result.getFrameCount()).isEqualTo(3);
        assertThat(result.isHistoryLimited()).isTrue();

        List<FrameVO> frames = result.getFrames();
        assertThat(frames).hasSize(3);

        // Frame 0
        assertThat(frames.get(0).getFrameIndex()).isEqualTo(0);
        assertThat(frames.get(0).getAgentRunId()).isEqualTo(100L);
        assertThat(frames.get(0).getChangedFilePaths()).containsExactly("src/Foo.java");
        assertThat(frames.get(0).getChangedFileCount()).isEqualTo(1);
        assertThat(frames.get(0).getTouchedSymbolCount()).isEqualTo(2);

        // Frame 1
        assertThat(frames.get(1).getFrameIndex()).isEqualTo(1);
        assertThat(frames.get(1).getAgentRunId()).isEqualTo(101L);
        assertThat(frames.get(1).getChangedFilePaths()).containsExactly("src/Bar.java");
        assertThat(frames.get(1).getChangedFileCount()).isEqualTo(1);
        assertThat(frames.get(1).getTouchedSymbolCount()).isEqualTo(3);

        // Frame 2
        assertThat(frames.get(2).getFrameIndex()).isEqualTo(2);
        assertThat(frames.get(2).getAgentRunId()).isEqualTo(102L);
        assertThat(frames.get(2).getChangedFilePaths()).containsExactlyInAnyOrder("src/Foo.java", "src/Bar.java");
        assertThat(frames.get(2).getChangedFileCount()).isEqualTo(2);
        assertThat(frames.get(2).getTouchedSymbolCount()).isEqualTo(5);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // getFrameGraph 场景
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void getFrameGraph_throwsForbiddenWhenNoPermission() {
        when(permissionService.checkRepoPermission(USER_ID, REPO_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.getFrameGraph(USER_ID, REPO_ID, 0))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.FORBIDDEN.getCode());
    }

    @Test
    void getFrameGraph_frameIndexOutOfBounds_throwsNotFound() {
        when(permissionService.checkRepoPermission(USER_ID, REPO_ID)).thenReturn(true);

        // 2 runs → valid indices 0,1 only
        AgentRunEntity run0 = makeRun(100L, REPO_ID, 1L, LocalDateTime.of(2026, 1, 1, 0, 0));
        AgentRunEntity run1 = makeRun(101L, REPO_ID, 2L, LocalDateTime.of(2026, 1, 2, 0, 0));
        when(agentRunMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(run0, run1));

        assertThatThrownBy(() -> service.getFrameGraph(USER_ID, REPO_ID, 2))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.NOT_FOUND.getCode());
    }

    @Test
    void getFrameGraph_negativeFrameIndex_throwsNotFound() {
        when(permissionService.checkRepoPermission(USER_ID, REPO_ID)).thenReturn(true);
        AgentRunEntity run0 = makeRun(100L, REPO_ID, 1L, LocalDateTime.of(2026, 1, 1, 0, 0));
        when(agentRunMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(run0));

        assertThatThrownBy(() -> service.getFrameGraph(USER_ID, REPO_ID, -1))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.NOT_FOUND.getCode());
    }

    @Test
    void getFrameGraph_emptyRunsWithIndex0_throwsNotFound() {
        when(permissionService.checkRepoPermission(USER_ID, REPO_ID)).thenReturn(true);
        when(agentRunMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> service.getFrameGraph(USER_ID, REPO_ID, 0))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.NOT_FOUND.getCode());
    }

    /**
     * 核心测试：2 帧，sym1 在帧0首见，sym2 在帧1首见，frameIndex=1。
     * 期望：
     *   - sym1: firstSeenFrame=0, touchCount=1 (只在帧0), changeType=STABLE (帧1未触碰)
     *   - sym2: firstSeenFrame=1, touchCount=1, changeType=NEW (帧1首见)
     */
    @Test
    void getFrameGraph_accumulatesFirstSeenFrameCorrectly() {
        when(permissionService.checkRepoPermission(USER_ID, REPO_ID)).thenReturn(true);

        AgentRunEntity run0 = makeRun(100L, REPO_ID, 1L, LocalDateTime.of(2026, 1, 1, 0, 0));
        AgentRunEntity run1 = makeRun(101L, REPO_ID, 2L, LocalDateTime.of(2026, 1, 2, 0, 0));
        when(agentRunMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(run0, run1));

        // code_file: Foo.java -> fileId=10, Bar.java -> fileId=11
        CodeFileEntity fileFoo = makeCodeFile(10L, REPO_ID, "src/Foo.java");
        CodeFileEntity fileBar = makeCodeFile(11L, REPO_ID, "src/Bar.java");
        when(codeFileMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(fileFoo, fileBar));

        // sym1 in Foo.java (fileId=10), sym2 in Bar.java (fileId=11)
        CodeSymbolEntity sym1 = makeSymbol(1L, REPO_ID, 10L, "com.example.FooService", "doFoo", SymbolType.SERVICE);
        CodeSymbolEntity sym2 = makeSymbol(2L, REPO_ID, 11L, "com.example.BarService", "doBar", SymbolType.SERVICE);

        // frame0: run0 touches Foo.java → sym1
        // frame1: run1 touches Bar.java → sym2
        final int[] fclCallIdx = {0};
        when(fileChangeLogMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenAnswer(inv -> {
                    int idx = fclCallIdx[0]++;
                    if (idx == 0) {
                        // frame0: Foo.java
                        return List.of(makeChangeLog(50L, REPO_ID, 100L, "src/Foo.java", "APPLIED"));
                    } else if (idx == 1) {
                        // frame1: Bar.java
                        return List.of(makeChangeLog(51L, REPO_ID, 101L, "src/Bar.java", "APPLIED"));
                    }
                    return Collections.emptyList();
                });

        // codeSymbolMapper for getSymbolIdsForRun calls (frame0 fileId=10 → sym1, frame1 fileId=11 → sym2)
        // then loadSymbols call (both sym1+sym2)
        final int[] symListCallIdx = {0};
        when(codeSymbolMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenAnswer(inv -> {
                    int idx = symListCallIdx[0]++;
                    if (idx == 0) return List.of(sym1); // frame0 symbol resolution
                    if (idx == 1) return List.of(sym2); // frame1 symbol resolution
                    if (idx == 2) return List.of(sym1, sym2); // loadSymbols for node building
                    return Collections.emptyList();
                });

        // no deps
        when(codeDependencyMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());

        CodeGraphVO result = service.getFrameGraph(USER_ID, REPO_ID, 1);

        assertThat(result.getNodes()).hasSize(2);

        Map<String, GraphNodeVO> nodeById = result.getNodes().stream()
                .collect(Collectors.toMap(GraphNodeVO::getId, n -> n));

        // sym1: seen in frame0, not touched in frame1 → STABLE
        GraphNodeVO node1 = nodeById.get("1");
        assertThat(node1).isNotNull();
        assertThat(node1.getFirstSeenFrame()).isEqualTo(0);
        assertThat(node1.getTouchCount()).isEqualTo(1);
        assertThat(node1.getChangeType()).isEqualTo("STABLE");

        // sym2: seen in frame1 (firstSeen=1==frameIndex=1) → NEW
        GraphNodeVO node2 = nodeById.get("2");
        assertThat(node2).isNotNull();
        assertThat(node2.getFirstSeenFrame()).isEqualTo(1);
        assertThat(node2.getTouchCount()).isEqualTo(1);
        assertThat(node2.getChangeType()).isEqualTo("NEW");
    }

    /**
     * changeType=MODIFIED: sym 在帧0首见，帧1再次触碰。frameIndex=1 时 changeType=MODIFIED。
     */
    @Test
    void getFrameGraph_changeType_modifiedWhenTouchedAgainInCurrentFrame() {
        when(permissionService.checkRepoPermission(USER_ID, REPO_ID)).thenReturn(true);

        AgentRunEntity run0 = makeRun(100L, REPO_ID, 1L, LocalDateTime.of(2026, 1, 1, 0, 0));
        AgentRunEntity run1 = makeRun(101L, REPO_ID, 2L, LocalDateTime.of(2026, 1, 2, 0, 0));
        when(agentRunMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(run0, run1));

        CodeFileEntity fileFoo = makeCodeFile(10L, REPO_ID, "src/Foo.java");
        when(codeFileMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(fileFoo));

        CodeSymbolEntity sym = makeSymbol(1L, REPO_ID, 10L, "com.example.Foo", "doFoo", SymbolType.SERVICE);

        // Both frames touch Foo.java → sym touched in frame0 AND frame1
        when(fileChangeLogMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(makeChangeLog(50L, REPO_ID, 100L, "src/Foo.java", "APPLIED")));

        // sym returned for frame0 resolve, frame1 resolve, loadSymbols
        when(codeSymbolMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(sym), List.of(sym), List.of(sym));

        when(codeDependencyMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());

        CodeGraphVO result = service.getFrameGraph(USER_ID, REPO_ID, 1);

        assertThat(result.getNodes()).hasSize(1);
        GraphNodeVO node = result.getNodes().get(0);
        assertThat(node.getFirstSeenFrame()).isEqualTo(0);
        assertThat(node.getTouchCount()).isEqualTo(2); // touched in both frames
        assertThat(node.getChangeType()).isEqualTo("MODIFIED"); // frame1 touched, but firstSeen=0 ≠ frameIndex=1
    }

    /**
     * touchCount 计数测试：sym 在 3 帧中各被触碰一次，fetchFrame=2 时 touchCount=3。
     */
    @Test
    void getFrameGraph_touchCount_countsAcrossAllFrames() {
        when(permissionService.checkRepoPermission(USER_ID, REPO_ID)).thenReturn(true);

        AgentRunEntity run0 = makeRun(100L, REPO_ID, 1L, LocalDateTime.of(2026, 1, 1, 0, 0));
        AgentRunEntity run1 = makeRun(101L, REPO_ID, 2L, LocalDateTime.of(2026, 1, 2, 0, 0));
        AgentRunEntity run2 = makeRun(102L, REPO_ID, 3L, LocalDateTime.of(2026, 1, 3, 0, 0));
        when(agentRunMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(run0, run1, run2));

        CodeFileEntity fileFoo = makeCodeFile(10L, REPO_ID, "src/Foo.java");
        when(codeFileMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(fileFoo));

        CodeSymbolEntity sym = makeSymbol(1L, REPO_ID, 10L, "com.example.Foo", "bar", SymbolType.SERVICE);

        // All 3 frames touch Foo.java (fileChangeLog returns same for all runs' calls)
        when(fileChangeLogMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(makeChangeLog(50L, REPO_ID, 100L, "src/Foo.java", "APPLIED")));

        // sym returned 3 times for symbol id resolution, then 1 time for loadSymbols
        when(codeSymbolMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(sym), List.of(sym), List.of(sym), List.of(sym));

        when(codeDependencyMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());

        CodeGraphVO result = service.getFrameGraph(USER_ID, REPO_ID, 2);

        assertThat(result.getNodes()).hasSize(1);
        GraphNodeVO node = result.getNodes().get(0);
        assertThat(node.getTouchCount()).isEqualTo(3);
        assertThat(node.getFirstSeenFrame()).isEqualTo(0);
        // frameIndex=2, sym touched in frame2 as well, firstSeen=0 ≠ 2 → MODIFIED
        assertThat(node.getChangeType()).isEqualTo("MODIFIED");
    }

    /**
     * 混合场景：frame0 sym1+sym2，frame1 仅 sym2，frameIndex=1。
     * sym1: firstSeenFrame=0, touchCount=1, changeType=STABLE（帧1未碰）。
     * sym2: firstSeenFrame=0, touchCount=2, changeType=MODIFIED（帧1碰，但非首见）。
     */
    @Test
    void getFrameGraph_changeType_mixedNewModifiedStable() {
        when(permissionService.checkRepoPermission(USER_ID, REPO_ID)).thenReturn(true);

        AgentRunEntity run0 = makeRun(100L, REPO_ID, 1L, LocalDateTime.of(2026, 1, 1, 0, 0));
        AgentRunEntity run1 = makeRun(101L, REPO_ID, 2L, LocalDateTime.of(2026, 1, 2, 0, 0));
        when(agentRunMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(run0, run1));

        CodeFileEntity fileFoo = makeCodeFile(10L, REPO_ID, "src/Foo.java");
        CodeFileEntity fileBar = makeCodeFile(11L, REPO_ID, "src/Bar.java");
        when(codeFileMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(fileFoo, fileBar));

        CodeSymbolEntity sym1 = makeSymbol(1L, REPO_ID, 10L, "Foo", "fooMethod", SymbolType.SERVICE);
        CodeSymbolEntity sym2 = makeSymbol(2L, REPO_ID, 11L, "Bar", "barMethod", SymbolType.SERVICE);

        // frame0: both Foo.java + Bar.java touched → sym1 + sym2
        // frame1: only Bar.java → sym2
        final int[] fclIdx = {0};
        when(fileChangeLogMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenAnswer(inv -> {
                    int i = fclIdx[0]++;
                    if (i == 0) return List.of(
                            makeChangeLog(50L, REPO_ID, 100L, "src/Foo.java", "APPLIED"),
                            makeChangeLog(51L, REPO_ID, 100L, "src/Bar.java", "APPLIED"));
                    if (i == 1) return List.of(
                            makeChangeLog(52L, REPO_ID, 101L, "src/Bar.java", "APPLIED"));
                    return Collections.emptyList();
                });

        // frame0 file resolution: Foo.java→fileId=10 + Bar.java→fileId=11 → sym1+sym2
        // frame1 file resolution: Bar.java→fileId=11 → sym2
        // loadSymbols: sym1+sym2
        final int[] symIdx = {0};
        when(codeSymbolMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenAnswer(inv -> {
                    int i = symIdx[0]++;
                    if (i == 0) return List.of(sym1, sym2); // frame0
                    if (i == 1) return List.of(sym2);       // frame1
                    if (i == 2) return List.of(sym1, sym2); // loadSymbols
                    return Collections.emptyList();
                });

        when(codeDependencyMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());

        CodeGraphVO result = service.getFrameGraph(USER_ID, REPO_ID, 1);

        assertThat(result.getNodes()).hasSize(2);
        Map<String, GraphNodeVO> nodeById = result.getNodes().stream()
                .collect(Collectors.toMap(GraphNodeVO::getId, n -> n));

        // sym1: firstSeen=0, touchCount=1 (only frame0), not in lastFrame → STABLE
        GraphNodeVO n1 = nodeById.get("1");
        assertThat(n1.getFirstSeenFrame()).isEqualTo(0);
        assertThat(n1.getTouchCount()).isEqualTo(1);
        assertThat(n1.getChangeType()).isEqualTo("STABLE");

        // sym2: firstSeen=0, touchCount=2, in lastFrame, firstSeen≠frameIndex → MODIFIED
        GraphNodeVO n2 = nodeById.get("2");
        assertThat(n2.getFirstSeenFrame()).isEqualTo(0);
        assertThat(n2.getTouchCount()).isEqualTo(2);
        assertThat(n2.getChangeType()).isEqualTo("MODIFIED");
    }

    /**
     * NEW changeType: sym 仅在最后一帧首次出现，且是该帧唯一触碰的符号。
     */
    @Test
    void getFrameGraph_changeType_newWhenFirstSeenInCurrentFrame() {
        when(permissionService.checkRepoPermission(USER_ID, REPO_ID)).thenReturn(true);

        AgentRunEntity run0 = makeRun(100L, REPO_ID, 1L, LocalDateTime.of(2026, 1, 1, 0, 0));
        when(agentRunMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(run0));

        CodeFileEntity fileFoo = makeCodeFile(10L, REPO_ID, "src/Foo.java");
        when(codeFileMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(fileFoo));

        CodeSymbolEntity sym = makeSymbol(42L, REPO_ID, 10L, "com.example.Foo", "create", SymbolType.SERVICE);

        when(fileChangeLogMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(makeChangeLog(50L, REPO_ID, 100L, "src/Foo.java", "APPLIED")));

        // symbol returned twice: once for getSymbolIds(frame0), once for loadSymbols
        when(codeSymbolMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(sym), List.of(sym));

        when(codeDependencyMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());

        CodeGraphVO result = service.getFrameGraph(USER_ID, REPO_ID, 0);

        assertThat(result.getNodes()).hasSize(1);
        GraphNodeVO node = result.getNodes().get(0);
        assertThat(node.getId()).isEqualTo("42");
        assertThat(node.getFirstSeenFrame()).isEqualTo(0);
        assertThat(node.getTouchCount()).isEqualTo(1);
        assertThat(node.getChangeType()).isEqualTo("NEW"); // firstSeen==frameIndex==0 → NEW
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Factory helpers
    // ═══════════════════════════════════════════════════════════════════════

    private AgentRunEntity makeRun(Long id, Long repoId, Long sessionId, LocalDateTime createdAt) {
        AgentRunEntity run = new AgentRunEntity();
        run.setId(id);
        run.setRepoId(repoId);
        run.setSessionId(sessionId);
        run.setCreatedAt(createdAt);
        return run;
    }

    private CodeFileEntity makeCodeFile(Long id, Long repoId, String filePath) {
        CodeFileEntity f = new CodeFileEntity();
        f.setId(id);
        f.setRepoId(repoId);
        f.setFilePath(filePath);
        return f;
    }

    private FileChangeLogEntity makeChangeLog(Long id, Long repoId, Long agentRunId, String filePath, String status) {
        FileChangeLogEntity log = new FileChangeLogEntity();
        log.setId(id);
        log.setRepoId(repoId);
        log.setAgentRunId(agentRunId);
        log.setFilePath(filePath);
        log.setStatus(status);
        return log;
    }

    private CodeSymbolEntity makeSymbol(Long id, Long repoId, Long fileId,
                                        String className, String methodName, SymbolType symbolType) {
        CodeSymbolEntity sym = new CodeSymbolEntity();
        sym.setId(id);
        sym.setRepoId(repoId);
        sym.setFileId(fileId);
        sym.setClassName(className);
        sym.setMethodName(methodName);
        sym.setSymbolType(symbolType);
        return sym;
    }
}
