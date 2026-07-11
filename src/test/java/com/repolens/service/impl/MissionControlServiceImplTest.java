package com.repolens.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.ComprehensionDebtFileEntity;
import com.repolens.domain.vo.AgentLaneVO;
import com.repolens.domain.vo.MissionControlVO;
import com.repolens.domain.vo.ReviewItemVO;
import com.repolens.domain.vo.RiskVO;
import com.repolens.domain.vo.SummaryVO;
import com.repolens.mapper.ComprehensionDebtFileMapper;
import com.repolens.security.PermissionService;
import com.repolens.service.impl.support.NativeAgentLaneProvider;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 单元测试：MissionControlServiceImpl（H Mission Control P1）。
 * Mock provider / permissionService / debtFileMapper，无 Spring 容器、无 DB。
 */
@ExtendWith(MockitoExtension.class)
class MissionControlServiceImplTest {

    @Mock private NativeAgentLaneProvider provider;
    @Mock private PermissionService permissionService;
    @Mock private ComprehensionDebtFileMapper debtFileMapper;

    @InjectMocks private MissionControlServiceImpl service;

    /**
     * 注册 MyBatis-Plus lambda 缓存，使 LambdaQueryWrapper 在无 Spring 上下文的单测中可用。
     */
    @BeforeAll
    static void initMybatisTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ComprehensionDebtFileEntity.class);
    }

    // ── Test 1: overview 正确聚合 summary ───────────────────────────────────────

    @Test
    void overview_aggregatesSummaryCorrectly() {
        when(permissionService.checkRepoPermission(1L, 10L)).thenReturn(true);

        // lane1: 2 BLOCK, 1 WARN, needsAttention=true
        RiskVO risk1 = new RiskVO();
        risk1.setBlockCount(2);
        risk1.setWarnCount(1);
        risk1.setHasIrreversibleBlock(true);
        AgentLaneVO lane1 = new AgentLaneVO();
        lane1.setLaneId(1L);
        lane1.setRisk(risk1);
        lane1.setNeedsAttention(true);

        // lane2: 0 BLOCK, 3 WARN, needsAttention=false
        RiskVO risk2 = new RiskVO();
        risk2.setBlockCount(0);
        risk2.setWarnCount(3);
        risk2.setHasIrreversibleBlock(false);
        AgentLaneVO lane2 = new AgentLaneVO();
        lane2.setLaneId(2L);
        lane2.setRisk(risk2);
        lane2.setNeedsAttention(false);

        when(provider.buildLanes(1L, 10L, 20)).thenReturn(List.of(lane1, lane2));
        when(provider.buildReviewQueue(10L)).thenReturn(List.of(new ReviewItemVO()));

        // Debt files: 1 RED, 2 YELLOW, 1 GREEN (GREEN should not count)
        ComprehensionDebtFileEntity red = buildDebtFile("RED");
        ComprehensionDebtFileEntity yellow1 = buildDebtFile("YELLOW");
        ComprehensionDebtFileEntity yellow2 = buildDebtFile("YELLOW");
        ComprehensionDebtFileEntity green = buildDebtFile("GREEN");
        when(debtFileMapper.selectList(any())).thenReturn(List.of(red, yellow1, yellow2, green));

        MissionControlVO result = service.overview(1L, 10L);

        assertThat(result).isNotNull();
        assertThat(result.getLanes()).hasSize(2);
        assertThat(result.getReviewQueue()).hasSize(1);

        SummaryVO summary = result.getSummary();
        assertThat(summary.getLaneCount()).isEqualTo(2);
        assertThat(summary.getTotalBlockRisks()).isEqualTo(2);   // lane1:2 + lane2:0
        assertThat(summary.getTotalWarnRisks()).isEqualTo(4);    // lane1:1 + lane2:3
        assertThat(summary.getNeedsAttentionCount()).isEqualTo(1); // only lane1
        assertThat(summary.getRedDebtFiles()).isEqualTo(1);
        assertThat(summary.getYellowDebtFiles()).isEqualTo(2);
    }

    // ── Test 2: 无权限 → FORBIDDEN ────────────────────────────────────────────

    @Test
    void overview_noPermission_throwsForbidden() {
        when(permissionService.checkRepoPermission(anyLong(), anyLong())).thenReturn(false);

        assertThatThrownBy(() -> service.overview(1L, 10L))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode())
                        .isEqualTo(ErrorCode.FORBIDDEN.getCode()));
    }

    // ── Test 3: provider 整体抛 → 降级返回空 VO，不崩 ────────────────────────

    @Test
    void overview_providerThrows_returnsDegradedEmptyVo() {
        when(permissionService.checkRepoPermission(1L, 10L)).thenReturn(true);
        when(provider.buildLanes(1L, 10L, 20)).thenThrow(new RuntimeException("simulated provider failure"));

        MissionControlVO result = service.overview(1L, 10L);

        assertThat(result).isNotNull();
        assertThat(result.getLanes()).isEmpty();
        assertThat(result.getReviewQueue()).isEmpty();

        SummaryVO summary = result.getSummary();
        assertThat(summary.getLaneCount()).isEqualTo(0);
        assertThat(summary.getTotalBlockRisks()).isEqualTo(0);
        assertThat(summary.getTotalWarnRisks()).isEqualTo(0);
        assertThat(summary.getNeedsAttentionCount()).isEqualTo(0);
        assertThat(summary.getRedDebtFiles()).isEqualTo(0);
        assertThat(summary.getYellowDebtFiles()).isEqualTo(0);
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private ComprehensionDebtFileEntity buildDebtFile(String band) {
        ComprehensionDebtFileEntity entity = new ComprehensionDebtFileEntity();
        entity.setDebtBand(band);
        return entity;
    }
}
