package com.repolens.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.AgentRunEntity;
import com.repolens.domain.entity.CodeFileEntity;
import com.repolens.domain.entity.CodeSymbolEntity;
import com.repolens.domain.entity.FileChangeLogEntity;
import com.repolens.domain.enums.SymbolType;
import com.repolens.domain.vo.BlastSubgraphVO;
import com.repolens.domain.vo.ChangeGraphVO;
import com.repolens.domain.vo.CodeGraphVO;
import com.repolens.domain.vo.GraphEdgeVO;
import com.repolens.domain.vo.GraphNodeVO;
import com.repolens.mapper.AgentRunMapper;
import com.repolens.mapper.CodeFileMapper;
import com.repolens.mapper.CodeSymbolMapper;
import com.repolens.mapper.FileChangeLogMapper;
import com.repolens.security.PermissionService;
import com.repolens.service.impl.ChangeGraphServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChangeGraphServiceImpl 单测：权限拒绝、run 不存在、归属不符、无改动、正常图、截断标志。
 */
class ChangeGraphServiceImplTest {

    private PermissionService permissionService;
    private AgentRunMapper agentRunMapper;
    private FileChangeLogMapper fileChangeLogMapper;
    private CodeSymbolMapper codeSymbolMapper;
    private CodeFileMapper codeFileMapper;
    private CodeGraphService codeGraphService;
    private ChangeGraphServiceImpl service;

    @BeforeEach
    void setup() {
        permissionService = mock(PermissionService.class);
        agentRunMapper = mock(AgentRunMapper.class);
        fileChangeLogMapper = mock(FileChangeLogMapper.class);
        codeSymbolMapper = mock(CodeSymbolMapper.class);
        codeFileMapper = mock(CodeFileMapper.class);
        codeGraphService = mock(CodeGraphService.class);
        service = new ChangeGraphServiceImpl(
                agentRunMapper, fileChangeLogMapper, codeSymbolMapper,
                codeFileMapper, codeGraphService, permissionService);
    }

    @Test
    void getChangeGraph_throwsForbiddenWhenNoPermission() {
        when(permissionService.checkRepoPermission(1L, 7L)).thenReturn(false);

        assertThatThrownBy(() -> service.getChangeGraph(1L, 7L, 100L))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.FORBIDDEN.getCode());

        verify(agentRunMapper, never()).selectById(any());
    }

    @Test
    void getChangeGraph_throwsNotFoundWhenRunMissing() {
        when(permissionService.checkRepoPermission(1L, 7L)).thenReturn(true);
        when(agentRunMapper.selectById(100L)).thenReturn(null);

        assertThatThrownBy(() -> service.getChangeGraph(1L, 7L, 100L))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.NOT_FOUND.getCode());
    }

    @Test
    void getChangeGraph_throwsForbiddenWhenRunBelongsToOtherRepo() {
        when(permissionService.checkRepoPermission(1L, 7L)).thenReturn(true);
        AgentRunEntity run = new AgentRunEntity();
        run.setId(100L);
        run.setRepoId(99L); // different repo!
        run.setSessionId(5L);
        when(agentRunMapper.selectById(100L)).thenReturn(run);

        assertThatThrownBy(() -> service.getChangeGraph(1L, 7L, 100L))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.FORBIDDEN.getCode());
    }

    @Test
    void getChangeGraph_returnsEmptyWhenNoSessionId() {
        when(permissionService.checkRepoPermission(1L, 7L)).thenReturn(true);
        AgentRunEntity run = new AgentRunEntity();
        run.setId(100L);
        run.setRepoId(7L);
        run.setSessionId(null);
        when(agentRunMapper.selectById(100L)).thenReturn(run);

        ChangeGraphVO result = service.getChangeGraph(1L, 7L, 100L);

        assertThat(result.getChangedFiles()).isEmpty();
        assertThat(result.getChangedSymbols()).isEmpty();
        assertThat(result.isTruncated()).isFalse();
        verify(fileChangeLogMapper, never()).selectList(any());
    }

    @Test
    void getChangeGraph_returnsEmptyWhenNoFileLogs() {
        when(permissionService.checkRepoPermission(1L, 7L)).thenReturn(true);
        AgentRunEntity run = new AgentRunEntity();
        run.setId(100L);
        run.setRepoId(7L);
        run.setSessionId(5L);
        when(agentRunMapper.selectById(100L)).thenReturn(run);
        when(fileChangeLogMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.emptyList());

        ChangeGraphVO result = service.getChangeGraph(1L, 7L, 100L);

        assertThat(result.getChangedFiles()).isEmpty();
        assertThat(result.getChangedSymbols()).isEmpty();
    }

    @Test
    void getChangeGraph_returnsPopulatedGraphWithChangedNodes() {
        when(permissionService.checkRepoPermission(1L, 7L)).thenReturn(true);

        AgentRunEntity run = new AgentRunEntity();
        run.setId(100L);
        run.setRepoId(7L);
        run.setSessionId(5L);
        when(agentRunMapper.selectById(100L)).thenReturn(run);

        FileChangeLogEntity log = new FileChangeLogEntity();
        log.setId(50L);
        log.setFilePath("src/UserService.java");
        log.setStatus("APPLIED");
        log.setSessionId(5L);
        log.setRepoId(7L);
        when(fileChangeLogMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(log));

        CodeFileEntity file = new CodeFileEntity();
        file.setId(10L);
        file.setFilePath("src/UserService.java");
        file.setRepoId(7L);
        when(codeFileMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(file));

        CodeSymbolEntity sym = new CodeSymbolEntity();
        sym.setId(42L);
        sym.setRepoId(7L);
        sym.setFileId(10L);
        sym.setClassName("com.example.UserService");
        sym.setMethodName("save");
        sym.setSymbolType(SymbolType.SERVICE);
        when(codeSymbolMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(sym));

        CodeGraphVO emptyGraph = CodeGraphVO.builder()
                .nodes(Collections.emptyList()).edges(Collections.emptyList())
                .nodeCount(0).edgeCount(0).truncated(false).build();
        when(codeGraphService.buildGraph(anyLong(), anyLong(), anyLong(), anyString(), anyInt(), anyDouble()))
                .thenReturn(emptyGraph);

        ChangeGraphVO result = service.getChangeGraph(1L, 7L, 100L);

        assertThat(result.getChangedFiles()).hasSize(1);
        assertThat(result.getChangedFiles().get(0).getFilePath()).isEqualTo("src/UserService.java");
        assertThat(result.getChangedFiles().get(0).getChangeLogId()).isEqualTo(50L);
        assertThat(result.getChangedSymbols()).hasSize(1);
        assertThat(result.getChangedSymbols().get(0).getChangeType()).isEqualTo("MODIFIED");
        assertThat(result.getChangedSymbols().get(0).getId()).isEqualTo("42");
        assertThat(result.isTruncated()).isFalse();
    }

    @Test
    void getChangeGraph_truncatedWhenTooManySymbols() {
        when(permissionService.checkRepoPermission(1L, 7L)).thenReturn(true);

        AgentRunEntity run = new AgentRunEntity();
        run.setId(100L);
        run.setRepoId(7L);
        run.setSessionId(5L);
        when(agentRunMapper.selectById(100L)).thenReturn(run);

        FileChangeLogEntity log = new FileChangeLogEntity();
        log.setId(50L);
        log.setFilePath("src/BigService.java");
        log.setStatus("APPLIED");
        when(fileChangeLogMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(log));

        CodeFileEntity file = new CodeFileEntity();
        file.setId(10L);
        file.setFilePath("src/BigService.java");
        when(codeFileMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(file));

        // 21 symbols → triggers truncation at MAX_CHANGED_SYMBOLS=20
        List<CodeSymbolEntity> manySymbols = new java.util.ArrayList<>();
        for (int i = 1; i <= 21; i++) {
            CodeSymbolEntity s = new CodeSymbolEntity();
            s.setId((long) i);
            s.setRepoId(7L);
            s.setFileId(10L);
            s.setMethodName("method" + i);
            manySymbols.add(s);
        }
        when(codeSymbolMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(manySymbols);

        // Return a unique node per symbol so we can assert upstream/downstream are populated.
        when(codeGraphService.buildGraph(anyLong(), anyLong(), anyLong(), eq("callers"), anyInt(), anyDouble()))
                .thenAnswer(inv -> {
                    Long symId = inv.getArgument(2);
                    GraphNodeVO n = GraphNodeVO.builder().id("caller-" + symId).label("caller").build();
                    GraphEdgeVO e = GraphEdgeVO.builder()
                            .id("ec-" + symId).source("caller-" + symId).target(String.valueOf(symId)).build();
                    return CodeGraphVO.builder().nodes(List.of(n)).edges(List.of(e))
                            .nodeCount(1).edgeCount(1).truncated(false).build();
                });
        when(codeGraphService.buildGraph(anyLong(), anyLong(), anyLong(), eq("callees"), anyInt(), anyDouble()))
                .thenAnswer(inv -> {
                    Long symId = inv.getArgument(2);
                    GraphNodeVO n = GraphNodeVO.builder().id("callee-" + symId).label("callee").build();
                    GraphEdgeVO e = GraphEdgeVO.builder()
                            .id("ed-" + symId).source(String.valueOf(symId)).target("callee-" + symId).build();
                    return CodeGraphVO.builder().nodes(List.of(n)).edges(List.of(e))
                            .nodeCount(1).edgeCount(1).truncated(false).build();
                });

        ChangeGraphVO result = service.getChangeGraph(1L, 7L, 100L);

        assertThat(result.isTruncated()).isTrue();
        assertThat(result.getChangedSymbols()).hasSize(20);
        // buildGraph must have been invoked for all 20 retained symbols × 2 directions
        verify(codeGraphService, times(40)).buildGraph(anyLong(), anyLong(), anyLong(), anyString(), anyInt(), anyDouble());
        // upstream and downstream must be populated for the retained symbols — bug #1 would leave these empty
        assertThat(result.getUpstream().getNodes()).hasSize(20);
        assertThat(result.getDownstream().getNodes()).hasSize(20);
    }

    @Test
    void getChangeGraph_deduplicatesSharedNodesAndExcludesChangedRoot() {
        when(permissionService.checkRepoPermission(1L, 7L)).thenReturn(true);

        AgentRunEntity run = new AgentRunEntity();
        run.setId(100L);
        run.setRepoId(7L);
        run.setSessionId(5L);
        when(agentRunMapper.selectById(100L)).thenReturn(run);

        FileChangeLogEntity log = new FileChangeLogEntity();
        log.setId(50L);
        log.setFilePath("src/Svc.java");
        log.setStatus("APPLIED");
        when(fileChangeLogMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(log));

        CodeFileEntity file = new CodeFileEntity();
        file.setId(10L);
        file.setFilePath("src/Svc.java");
        when(codeFileMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(file));

        // Two changed symbols: sym1 (id=1) and sym2 (id=2), both in fileId=10
        CodeSymbolEntity sym1 = new CodeSymbolEntity();
        sym1.setId(1L); sym1.setRepoId(7L); sym1.setFileId(10L); sym1.setMethodName("alpha");
        CodeSymbolEntity sym2 = new CodeSymbolEntity();
        sym2.setId(2L); sym2.setRepoId(7L); sym2.setFileId(10L); sym2.setMethodName("beta");
        when(codeSymbolMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(sym1, sym2));

        // sym1 callers: shared node "100" + changed-root "1" (must be excluded); dangling edge
        GraphNodeVO n100 = GraphNodeVO.builder().id("100").label("shared-up").build();
        GraphNodeVO nChanged1 = GraphNodeVO.builder().id("1").label("changed-root").build();
        GraphEdgeVO eUp1Valid = GraphEdgeVO.builder()
                .id("ec-1-A").source("100").target("1").build();
        GraphEdgeVO eUp1Dangling = GraphEdgeVO.builder()
                .id("ec-dangling").source("999").target("1").build(); // source "999" not in any node set
        CodeGraphVO sym1Callers = CodeGraphVO.builder()
                .nodes(List.of(n100, nChanged1)).edges(List.of(eUp1Valid, eUp1Dangling))
                .nodeCount(2).edgeCount(2).truncated(false).build();

        // sym2 callers: "100" shared (dedup) + unique "101"
        GraphNodeVO n101 = GraphNodeVO.builder().id("101").label("uniq-up").build();
        GraphEdgeVO eUp2A = GraphEdgeVO.builder()
                .id("ec-2-A").source("100").target("2").build();
        GraphEdgeVO eUp2B = GraphEdgeVO.builder()
                .id("ec-2-B").source("101").target("2").build();
        CodeGraphVO sym2Callers = CodeGraphVO.builder()
                .nodes(List.of(n100, n101)).edges(List.of(eUp2A, eUp2B))
                .nodeCount(2).edgeCount(2).truncated(false).build();

        // sym1 callees: shared "200" + changed-root "2" (must be excluded)
        GraphNodeVO n200 = GraphNodeVO.builder().id("200").label("shared-down").build();
        GraphNodeVO nChanged2 = GraphNodeVO.builder().id("2").label("changed-root-2").build();
        GraphEdgeVO eDn1A = GraphEdgeVO.builder()
                .id("ed-1-A").source("1").target("200").build();
        CodeGraphVO sym1Callees = CodeGraphVO.builder()
                .nodes(List.of(n200, nChanged2)).edges(List.of(eDn1A))
                .nodeCount(2).edgeCount(1).truncated(false).build();

        // sym2 callees: "200" shared (dedup) + unique "201"; dangling edge
        GraphNodeVO n201 = GraphNodeVO.builder().id("201").label("uniq-down").build();
        GraphEdgeVO eDn2A = GraphEdgeVO.builder()
                .id("ed-2-A").source("2").target("200").build();
        GraphEdgeVO eDnDangling = GraphEdgeVO.builder()
                .id("ed-dangling").source("888").target("999").build(); // both endpoints not in graph
        CodeGraphVO sym2Callees = CodeGraphVO.builder()
                .nodes(List.of(n200, n201)).edges(List.of(eDn2A, eDnDangling))
                .nodeCount(2).edgeCount(2).truncated(false).build();

        when(codeGraphService.buildGraph(eq(1L), eq(7L), eq(1L), eq("callers"), anyInt(), anyDouble()))
                .thenReturn(sym1Callers);
        when(codeGraphService.buildGraph(eq(1L), eq(7L), eq(2L), eq("callers"), anyInt(), anyDouble()))
                .thenReturn(sym2Callers);
        when(codeGraphService.buildGraph(eq(1L), eq(7L), eq(1L), eq("callees"), anyInt(), anyDouble()))
                .thenReturn(sym1Callees);
        when(codeGraphService.buildGraph(eq(1L), eq(7L), eq(2L), eq("callees"), anyInt(), anyDouble()))
                .thenReturn(sym2Callees);

        ChangeGraphVO result = service.getChangeGraph(1L, 7L, 100L);

        // Node dedup: "100" and "101" only, NOT "999", NOT "1" or "2" (changed roots)
        List<String> upstreamIds = result.getUpstream().getNodes().stream()
                .map(GraphNodeVO::getId).collect(java.util.stream.Collectors.toList());
        assertThat(upstreamIds).containsExactlyInAnyOrder("100", "101");

        // Node dedup: "200" and "201" only, NOT "888"/"999", NOT "2" (changed root)
        List<String> downstreamIds = result.getDownstream().getNodes().stream()
                .map(GraphNodeVO::getId).collect(java.util.stream.Collectors.toList());
        assertThat(downstreamIds).containsExactlyInAnyOrder("200", "201");

        // Upstream edge "ec-dangling" (source="999") must be filtered out
        List<String> upEdgeIds = result.getUpstream().getEdges().stream()
                .map(GraphEdgeVO::getId).collect(java.util.stream.Collectors.toList());
        assertThat(upEdgeIds).doesNotContain("ec-dangling");
        assertThat(upEdgeIds).contains("ec-1-A", "ec-2-A", "ec-2-B");

        // Downstream edge "ed-dangling" (source="888", target="999") must be filtered out
        List<String> dnEdgeIds = result.getDownstream().getEdges().stream()
                .map(GraphEdgeVO::getId).collect(java.util.stream.Collectors.toList());
        assertThat(dnEdgeIds).doesNotContain("ed-dangling");
        assertThat(dnEdgeIds).contains("ed-1-A", "ed-2-A");

        assertThat(result.isTruncated()).isFalse();
    }
}
