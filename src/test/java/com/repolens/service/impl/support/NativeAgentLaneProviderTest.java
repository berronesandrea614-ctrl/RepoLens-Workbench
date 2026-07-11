package com.repolens.service.impl.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.repolens.domain.entity.AgentRunEntity;
import com.repolens.domain.entity.AgentRunPlanEntity;
import com.repolens.domain.entity.ChangeRiskFlagEntity;
import com.repolens.domain.entity.ComprehensionDebtFileEntity;
import com.repolens.domain.entity.FileChangeLogEntity;
import com.repolens.domain.entity.RequirementEntity;
import com.repolens.domain.vo.AgentLaneVO;
import com.repolens.domain.vo.DeviationVO;
import com.repolens.domain.vo.ReconciliationVO;
import com.repolens.domain.vo.ReviewItemVO;
import com.repolens.domain.vo.RiskVO;
import com.repolens.mapper.AgentRunMapper;
import com.repolens.mapper.AgentRunPlanMapper;
import com.repolens.mapper.ChangeRiskFlagMapper;
import com.repolens.mapper.ComprehensionDebtFileMapper;
import com.repolens.mapper.FileChangeLogMapper;
import com.repolens.mapper.RequirementMapper;
import com.repolens.service.ReconciliationService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * TDD 单测：NativeAgentLaneProvider（H Mission Control P1）。
 * Mock 所有 mapper/service，验证泳道聚合逻辑与 fail-safe 降级行为。
 */
@ExtendWith(MockitoExtension.class)
class NativeAgentLaneProviderTest {

    @Mock AgentRunMapper agentRunMapper;
    @Mock AgentRunPlanMapper agentRunPlanMapper;
    @Mock FileChangeLogMapper fileChangeLogMapper;
    @Mock ChangeRiskFlagMapper changeRiskFlagMapper;
    @Mock ComprehensionDebtFileMapper comprehensionDebtFileMapper;
    @Mock RequirementMapper requirementMapper;
    @Mock ReconciliationService reconciliationService;

    @InjectMocks NativeAgentLaneProvider provider;

    /**
     * 手动注册 MyBatis-Plus lambda 缓存，使 LambdaQueryWrapper 可在无 Spring 上下文的单测中使用。
     * 与 RepoAsyncIndexServiceImplTest 相同模式。
     */
    @BeforeAll
    static void initMybatisTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AgentRunEntity.class);
        TableInfoHelper.initTableInfo(assistant, AgentRunPlanEntity.class);
        TableInfoHelper.initTableInfo(assistant, FileChangeLogEntity.class);
        TableInfoHelper.initTableInfo(assistant, ChangeRiskFlagEntity.class);
        TableInfoHelper.initTableInfo(assistant, ComprehensionDebtFileEntity.class);
        TableInfoHelper.initTableInfo(assistant, RequirementEntity.class);
    }

    // ── Test 1: 3 个 agent_run → 3 条泳道 ─────────────────────────────────────

    @Test
    void buildLanes_threeRuns_returnsThreeLanes() {
        when(comprehensionDebtFileMapper.selectList(any())).thenReturn(List.of());
        when(agentRunMapper.selectList(any())).thenReturn(
                List.of(buildRun(1L), buildRun(2L), buildRun(3L)));
        when(agentRunPlanMapper.selectOne(any())).thenReturn(null);
        when(fileChangeLogMapper.selectList(any())).thenReturn(List.of());
        when(requirementMapper.selectOne(any())).thenReturn(null);

        List<AgentLaneVO> lanes = provider.buildLanes(1L, 10L, 20);

        assertThat(lanes).hasSize(3);
        assertThat(lanes).allMatch(l -> !l.isDegraded());
        assertThat(lanes.get(0).getLaneId()).isEqualTo(1L);
        assertThat(lanes.get(1).getLaneId()).isEqualTo(2L);
        assertThat(lanes.get(2).getLaneId()).isEqualTo(3L);
    }

    // ── Test 2: planLine 取 approach ──────────────────────────────────────────

    @Test
    void buildLanes_planLine_takesApproachWhenPresent() {
        when(comprehensionDebtFileMapper.selectList(any())).thenReturn(List.of());
        when(agentRunMapper.selectList(any())).thenReturn(List.of(buildRun(1L)));
        AgentRunPlanEntity plan = new AgentRunPlanEntity();
        plan.setApproach("重构 UserService 提高可读性");
        when(agentRunPlanMapper.selectOne(any())).thenReturn(plan);
        when(fileChangeLogMapper.selectList(any())).thenReturn(List.of());
        when(requirementMapper.selectOne(any())).thenReturn(null);

        List<AgentLaneVO> lanes = provider.buildLanes(1L, 10L, 20);

        assertThat(lanes.get(0).getPlanLine()).isEqualTo("重构 UserService 提高可读性");
    }

    // ── Test 3: planLine 无计划降级 ────────────────────────────────────────────

    @Test
    void buildLanes_planLine_defaultsToFallbackWhenNoPlan() {
        when(comprehensionDebtFileMapper.selectList(any())).thenReturn(List.of());
        when(agentRunMapper.selectList(any())).thenReturn(List.of(buildRun(1L)));
        when(agentRunPlanMapper.selectOne(any())).thenReturn(null);
        when(fileChangeLogMapper.selectList(any())).thenReturn(List.of());
        when(requirementMapper.selectOne(any())).thenReturn(null);

        List<AgentLaneVO> lanes = provider.buildLanes(1L, 10L, 20);

        assertThat(lanes.get(0).getPlanLine()).isEqualTo("计划未结构化");
    }

    // ── Test 4: risk 聚合 blockCount / hasIrreversibleBlock ──────────────────

    @Test
    void buildLanes_risk_aggregatesBlockCountAndIrreversibleBlock() {
        when(comprehensionDebtFileMapper.selectList(any())).thenReturn(List.of());
        when(agentRunMapper.selectList(any())).thenReturn(List.of(buildRun(1L)));
        when(agentRunPlanMapper.selectOne(any())).thenReturn(null);
        when(fileChangeLogMapper.selectList(any())).thenReturn(
                List.of(buildChange(101L, 1L, "src/Service.java"),
                        buildChange(102L, 1L, "src/Mapper.java")));
        when(changeRiskFlagMapper.selectList(any())).thenReturn(
                List.of(buildRiskFlag(201L, 101L, "BLOCK", "IRREVERSIBLE"),
                        buildRiskFlag(202L, 102L, "WARN", "REVERSIBLE")));
        when(requirementMapper.selectOne(any())).thenReturn(null);

        List<AgentLaneVO> lanes = provider.buildLanes(1L, 10L, 20);

        RiskVO risk = lanes.get(0).getRisk();
        assertThat(risk.getBlockCount()).isEqualTo(1);
        assertThat(risk.getWarnCount()).isEqualTo(1);
        assertThat(risk.isHasIrreversibleBlock()).isTrue();
    }

    // ── Test 5: deviation 有 requirement → 填充 ────────────────────────────────

    @Test
    void buildLanes_deviation_filledWhenRequirementExists() {
        when(comprehensionDebtFileMapper.selectList(any())).thenReturn(List.of());
        when(agentRunMapper.selectList(any())).thenReturn(List.of(buildRun(1L)));
        when(agentRunPlanMapper.selectOne(any())).thenReturn(null);
        when(fileChangeLogMapper.selectList(any())).thenReturn(List.of());

        RequirementEntity req = new RequirementEntity();
        req.setId(50L);
        req.setAgentRunId(1L);
        when(requirementMapper.selectOne(any())).thenReturn(req);

        ReconciliationVO recon = ReconciliationVO.builder()
                .planned(true)
                .summary(ReconciliationVO.Summary.builder()
                        .coverage(0.8)
                        .trustFlag("OK")
                        .offPlanCount(2)
                        .build())
                .items(List.of(
                        ReconciliationVO.PlanItemRecon.builder().status("MISSING_SILENT").build(),
                        ReconciliationVO.PlanItemRecon.builder().status("LANDED").build()))
                .build();
        when(reconciliationService.getOrCompute(1L, 10L, 50L)).thenReturn(recon);

        List<AgentLaneVO> lanes = provider.buildLanes(1L, 10L, 20);

        DeviationVO dv = lanes.get(0).getDeviation();
        assertThat(dv).isNotNull();
        assertThat(dv.isPlanned()).isTrue();
        assertThat(dv.getCoverage()).isEqualTo(80);
        assertThat(dv.getTrustFlag()).isEqualTo("OK");
        assertThat(dv.getMissingCount()).isEqualTo(1);
        assertThat(dv.getOffPlanCount()).isEqualTo(2);
    }

    // ── Test 6: deviation 无 requirement → null ───────────────────────────────

    @Test
    void buildLanes_deviation_nullWhenNoRequirement() {
        when(comprehensionDebtFileMapper.selectList(any())).thenReturn(List.of());
        when(agentRunMapper.selectList(any())).thenReturn(List.of(buildRun(1L)));
        when(agentRunPlanMapper.selectOne(any())).thenReturn(null);
        when(fileChangeLogMapper.selectList(any())).thenReturn(List.of());
        when(requirementMapper.selectOne(any())).thenReturn(null);

        List<AgentLaneVO> lanes = provider.buildLanes(1L, 10L, 20);

        assertThat(lanes.get(0).getDeviation()).isNull();
    }

    // ── Test 7: needsAttention = true 当 BLOCK > 0 ───────────────────────────

    @Test
    void buildLanes_needsAttention_trueWhenBlockCountGreaterThanZero() {
        when(comprehensionDebtFileMapper.selectList(any())).thenReturn(List.of());
        when(agentRunMapper.selectList(any())).thenReturn(List.of(buildRun(1L)));
        when(agentRunPlanMapper.selectOne(any())).thenReturn(null);
        when(fileChangeLogMapper.selectList(any())).thenReturn(
                List.of(buildChange(101L, 1L, "src/Service.java")));
        when(changeRiskFlagMapper.selectList(any())).thenReturn(
                List.of(buildRiskFlag(201L, 101L, "BLOCK", "REVERSIBLE")));
        when(requirementMapper.selectOne(any())).thenReturn(null);

        List<AgentLaneVO> lanes = provider.buildLanes(1L, 10L, 20);

        AgentLaneVO lane = lanes.get(0);
        assertThat(lane.isNeedsAttention()).isTrue();
        assertThat(lane.getRisk().getBlockCount()).isEqualTo(1);
        assertThat(lane.getRisk().isHasIrreversibleBlock()).isFalse();
    }

    // ── Test 8: 单 run 抛异常 → degraded 占位，不影响其它泳道 ─────────────────

    @Test
    void buildLanes_singleRunException_degradedLaneDoesNotAffectOthers() {
        when(comprehensionDebtFileMapper.selectList(any())).thenReturn(List.of());
        when(agentRunMapper.selectList(any())).thenReturn(
                List.of(buildRun(1L), buildRun(2L), buildRun(3L)));
        // All 3 runs call agentRunPlanMapper.selectOne first; run2 then fails at fileChangeLogMapper
        when(agentRunPlanMapper.selectOne(any())).thenReturn(null);
        when(fileChangeLogMapper.selectList(any()))
                .thenReturn(List.of())                                      // run1: ok
                .thenThrow(new RuntimeException("simulated DB error"))      // run2: throws → degraded
                .thenReturn(List.of());                                     // run3: ok
        when(requirementMapper.selectOne(any())).thenReturn(null);

        List<AgentLaneVO> lanes = provider.buildLanes(1L, 10L, 20);

        assertThat(lanes).hasSize(3);
        assertThat(lanes.get(0).isDegraded()).isFalse();
        assertThat(lanes.get(1).isDegraded()).isTrue();
        assertThat(lanes.get(1).getLaneId()).isEqualTo(2L);
        assertThat(lanes.get(1).getStatus()).isEqualTo("ERROR");
        assertThat(lanes.get(2).isDegraded()).isFalse();
        assertThat(lanes.get(2).getLaneId()).isEqualTo(3L);
    }

    // ── Test 9: buildReviewQueue interrupt 判定（IRREVERSIBLE&&BLOCK → true）──

    @Test
    void buildReviewQueue_interrupt_trueForIrreversibleBlockFalseOtherwise() {
        ChangeRiskFlagEntity flag1 = buildRiskFlag(1L, 101L, "BLOCK", "IRREVERSIBLE");
        flag1.setCategory("DESTRUCTIVE");
        flag1.setEvidence("DROP TABLE users;");
        ChangeRiskFlagEntity flag2 = buildRiskFlag(2L, 102L, "WARN", "REVERSIBLE");
        flag2.setCategory("SCOPE");
        flag2.setEvidence("side-effect write");
        when(changeRiskFlagMapper.selectList(any())).thenReturn(List.of(flag1, flag2));

        FileChangeLogEntity fcl1 = new FileChangeLogEntity();
        fcl1.setId(101L);
        fcl1.setFilePath("src/main/resources/schema.sql");
        FileChangeLogEntity fcl2 = new FileChangeLogEntity();
        fcl2.setId(102L);
        fcl2.setFilePath("src/main/resources/cleanup.sql");
        when(fileChangeLogMapper.selectById(101L)).thenReturn(fcl1);
        when(fileChangeLogMapper.selectById(102L)).thenReturn(fcl2);

        List<ReviewItemVO> queue = provider.buildReviewQueue(10L);

        assertThat(queue).hasSize(2);

        ReviewItemVO item1 = queue.get(0);
        assertThat(item1.isInterrupt()).isTrue();
        assertThat(item1.getSeverity()).isEqualTo("BLOCK");
        assertThat(item1.getReversibility()).isEqualTo("IRREVERSIBLE");
        assertThat(item1.getKind()).isEqualTo("DESTRUCTIVE");
        assertThat(item1.getFilePath()).isEqualTo("src/main/resources/schema.sql");
        assertThat(item1.getEvidence()).isEqualTo("DROP TABLE users;");

        ReviewItemVO item2 = queue.get(1);
        assertThat(item2.isInterrupt()).isFalse();
        assertThat(item2.getFilePath()).isEqualTo("src/main/resources/cleanup.sql");
    }

    // ── Helper builders ───────────────────────────────────────────────────────

    private AgentRunEntity buildRun(Long id) {
        AgentRunEntity run = new AgentRunEntity();
        run.setId(id);
        run.setRepoId(10L);
        run.setStatus("DONE");
        run.setClaimedSuccess(1);
        run.setClaimedVerified(0);
        return run;
    }

    private FileChangeLogEntity buildChange(Long id, Long agentRunId, String filePath) {
        FileChangeLogEntity c = new FileChangeLogEntity();
        c.setId(id);
        c.setAgentRunId(agentRunId);
        c.setFilePath(filePath);
        return c;
    }

    private ChangeRiskFlagEntity buildRiskFlag(Long id, Long changeId,
                                                String severity, String reversibility) {
        ChangeRiskFlagEntity flag = new ChangeRiskFlagEntity();
        flag.setId(id);
        flag.setChangeId(changeId);
        flag.setRepoId(10L);
        flag.setSeverity(severity);
        flag.setReversibility(reversibility);
        flag.setAcknowledged(0);
        return flag;
    }
}
