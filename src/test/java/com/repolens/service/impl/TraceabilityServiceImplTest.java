package com.repolens.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.domain.entity.CodeSymbolEntity;
import com.repolens.domain.entity.RequirementEntity;
import com.repolens.domain.entity.RequirementSymbolEntity;
import com.repolens.domain.entity.TraceSnapshotEntity;
import com.repolens.domain.enums.SymbolType;
import com.repolens.domain.vo.TraceForwardVO;
import com.repolens.domain.vo.TraceMapVO;
import com.repolens.domain.vo.TraceReverseVO;
import com.repolens.mapper.*;
import com.repolens.security.PermissionService;
import com.repolens.service.MilvusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TraceabilityServiceImplTest {

    @Mock RequirementSymbolMapper requirementSymbolMapper;
    @Mock TraceSnapshotMapper     traceSnapshotMapper;
    @Mock RequirementMapper       requirementMapper;
    @Mock CodeSymbolMapper        codeSymbolMapper;
    @Mock CodeFileMapper          codeFileMapper;
    @Mock CodeChunkMapper         codeChunkMapper;
    @Mock AgentRunMapper          agentRunMapper;
    @Mock AgentRunPlanMapper      agentRunPlanMapper;
    @Mock FileChangeLogMapper     fileChangeLogMapper;
    @Mock MilvusService           milvusService;
    @Mock PermissionService       permissionService;
    @Mock CodeDependencyMapper    codeDependencyMapper;
    @Mock com.repolens.llm.LlmClient llmClient;
    @Mock com.repolens.llm.config.LlmRuntimeConfig llmRuntimeConfig;

    TraceabilityServiceImpl svc;

    @BeforeEach
    void setUp() {
        when(permissionService.checkRepoPermission(anyLong(), anyLong())).thenReturn(true);
        svc = new TraceabilityServiceImpl(
                requirementSymbolMapper, traceSnapshotMapper, requirementMapper,
                codeSymbolMapper, codeFileMapper, codeChunkMapper,
                agentRunMapper, agentRunPlanMapper, fileChangeLogMapper,
                milvusService, permissionService, new ObjectMapper(),
                codeDependencyMapper, llmClient, llmRuntimeConfig);
    }

    @Test
    void getOrComputeMap_noRequirements_returnsPerfect() {
        when(requirementMapper.selectList(any())).thenReturn(List.of());
        when(codeSymbolMapper.selectList(any())).thenReturn(List.of());
        when(requirementSymbolMapper.selectList(any())).thenReturn(List.of());
        when(traceSnapshotMapper.selectOne(any())).thenReturn(null);
        // insert returns 0 by default — that's fine (failure-safe)

        TraceMapVO result = svc.getOrComputeMap(1L, 1L);
        assertThat(result.getMetrics().getCoverage()).isEqualTo(1.0);
        assertThat(result.getMetrics().getOrphanCount()).isEqualTo(0);
        assertThat(result.getMetrics().getDanglingCount()).isEqualTo(0);
    }

    @Test
    void getOrComputeMap_cachedSnapshot_returnsCached() throws Exception {
        TraceSnapshotEntity snap = new TraceSnapshotEntity();
        snap.setCoverage(0.75);
        snap.setOrphanCount(3);
        snap.setDanglingCount(1);
        snap.setStaleCount(0);
        snap.setDegraded(0);
        TraceMapVO cached = TraceMapVO.builder()
                .metrics(TraceMapVO.Metrics.builder()
                        .coverage(0.75).orphanCount(3).danglingCount(1).staleCount(0).build())
                .nodes(List.of()).edges(List.of()).degraded(false).build();
        snap.setDetailJson(new ObjectMapper().writeValueAsString(cached));
        snap.setComputedAt(LocalDateTime.now());
        when(traceSnapshotMapper.selectOne(any())).thenReturn(snap);

        TraceMapVO result = svc.getOrComputeMap(1L, 1L);
        assertThat(result.getMetrics().getCoverage()).isEqualTo(0.75);
        verify(requirementMapper, never()).selectList(any());
    }

    @Test
    void forwardTrace_noLinks_returnsDangling() {
        RequirementEntity req = new RequirementEntity();
        req.setId(5L); req.setRepoId(1L); req.setTitle("Add login");
        when(requirementMapper.selectById(5L)).thenReturn(req);
        when(requirementSymbolMapper.selectList(any())).thenReturn(List.of());

        TraceForwardVO result = svc.forwardTrace(1L, 1L, 5L);
        assertThat(result.getCoverage()).isEqualTo(0.0);
        assertThat(result.getLinks()).isEmpty();
    }

    @Test
    void reverseTrace_noLinks_returnsEmpty() {
        CodeSymbolEntity sym = new CodeSymbolEntity();
        sym.setId(10L); sym.setRepoId(1L); sym.setClassName("FooService");
        sym.setSymbolType(SymbolType.SERVICE);
        when(codeSymbolMapper.selectById(10L)).thenReturn(sym);
        when(requirementSymbolMapper.selectList(any())).thenReturn(List.of());
        when(requirementMapper.selectList(any())).thenReturn(List.of());

        TraceReverseVO result = svc.reverseTrace(1L, 1L, 10L);
        assertThat(result.getRequirements()).isEmpty();
        assertThat(result.getLayer()).isEqualTo("Service");
    }

    @Test
    void markDechainSafe_fileDeleted_returnsBroken() {
        RequirementSymbolEntity link = new RequirementSymbolEntity();
        link.setId(1L); link.setRequirementId(42L);
        link.setFilePath("src/Foo.java");
        link.setStatus(RequirementSymbolEntity.STATUS_LINKED);
        when(requirementSymbolMapper.selectList(any())).thenReturn(List.of(link));
        // updateById returns 0 by default — fine

        List<Long> affected = svc.markDechainSafe(1L, "src/Foo.java", true);
        assertThat(affected).containsExactly(42L);
        assertThat(link.getStatus()).isEqualTo(RequirementSymbolEntity.STATUS_BROKEN);
    }

    @Test
    void markDechainSafe_fileChanged_returnsStale() {
        RequirementSymbolEntity link = new RequirementSymbolEntity();
        link.setId(2L); link.setRequirementId(99L);
        link.setFilePath("src/Bar.java");
        link.setStatus(RequirementSymbolEntity.STATUS_LINKED);
        when(requirementSymbolMapper.selectList(any())).thenReturn(List.of(link));
        // updateById returns 0 by default — fine

        List<Long> affected = svc.markDechainSafe(1L, "src/Bar.java", false);
        assertThat(affected).containsExactly(99L);
        assertThat(link.getStatus()).isEqualTo(RequirementSymbolEntity.STATUS_STALE);
    }

    @Test
    void recompute_noDependencies_graceful() {
        when(requirementMapper.selectList(any())).thenReturn(List.of());
        when(codeSymbolMapper.selectList(any())).thenReturn(List.of());
        when(requirementSymbolMapper.selectList(any())).thenReturn(List.of());
        when(traceSnapshotMapper.selectOne(any())).thenReturn(null);

        TraceMapVO result = svc.recompute(1L, 1L);
        assertThat(result).isNotNull();
    }
}
