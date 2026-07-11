package com.repolens.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.AgentRunEntity;
import com.repolens.domain.entity.AgentRunPlanEntity;
import com.repolens.domain.entity.AgentRunStepEntity;
import com.repolens.domain.entity.FileChangeLogEntity;
import com.repolens.domain.entity.RequirementEntity;
import com.repolens.domain.entity.RequirementReconciliationEntity;
import com.repolens.domain.entity.ToolCallLogEntity;
import com.repolens.domain.vo.ReconciliationVO;
import com.repolens.mapper.AgentRunMapper;
import com.repolens.mapper.AgentRunPlanMapper;
import com.repolens.mapper.AgentRunStepMapper;
import com.repolens.mapper.FileChangeLogMapper;
import com.repolens.mapper.RequirementMapper;
import com.repolens.mapper.RequirementReconciliationMapper;
import com.repolens.mapper.ToolCallLogMapper;
import com.repolens.security.PermissionService;
import com.repolens.service.impl.support.AgentRulesLoader;
import com.repolens.service.impl.support.ConstraintRule;
import com.repolens.service.impl.support.ConstraintRuleCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * ReconciliationServiceImpl 单元测试 + 验收场景（Feature B P1）。
 * 验收用 fixture 数据，无真实 DB 连接。
 *
 * <h2>验收场景</h2>
 * <ol>
 *   <li>声明改3文件、实际多改 SecurityConfig 且删1处测试断言、答案说"已测试通过"但没调 runVerification
 *       → SecurityConfig SILENT_ADD + TEST_WEAKENED + FABRICATED_VERIFICATION + trustFlag=FABRICATED。</li>
 *   <li>干净会话：5步全落实、无计划外、跑了测试通过 → coverage=100% 无红 trustFlag=OK。</li>
 *   <li>老会话（无计划）：graceful degrade，planned=false，不报错。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class ReconciliationServiceImplTest {

    private static final Long USER_ID = 1L;
    private static final Long REPO_ID = 2L;
    private static final Long REQ_ID  = 10L;
    private static final Long RUN_ID  = 50L;
    private static final Long SESSION_ID = 100L;

    @Mock private RequirementMapper requirementMapper;
    @Mock private AgentRunMapper agentRunMapper;
    @Mock private AgentRunPlanMapper agentRunPlanMapper;
    @Mock private AgentRunStepMapper agentRunStepMapper;
    @Mock private FileChangeLogMapper fileChangeLogMapper;
    @Mock private ToolCallLogMapper toolCallLogMapper;
    @Mock private RequirementReconciliationMapper reconciliationMapper;
    @Mock private PermissionService permissionService;
    @Mock private AgentRulesLoader agentRulesLoader;
    @Mock private ConstraintRuleCacheService constraintRuleCacheService;

    private ReconciliationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ReconciliationServiceImpl(
                requirementMapper, agentRunMapper, agentRunPlanMapper,
                agentRunStepMapper, fileChangeLogMapper, toolCallLogMapper,
                reconciliationMapper, permissionService, new ObjectMapper(),
                agentRulesLoader, constraintRuleCacheService);

        lenient().when(permissionService.checkRepoPermission(USER_ID, REPO_ID)).thenReturn(true);
        lenient().when(reconciliationMapper.selectOne(any())).thenReturn(null);
        lenient().when(reconciliationMapper.delete(any())).thenReturn(0);
        lenient().when(reconciliationMapper.insert(any(RequirementReconciliationEntity.class))).thenReturn(1);
        lenient().when(agentRunStepMapper.selectList(any())).thenReturn(List.of());
        // Default: no AGENTS.md → no constraint rules
        lenient().when(agentRulesLoader.loadRules(any(Long.class))).thenReturn(null);
        lenient().when(constraintRuleCacheService.loadOrParse(any(Long.class), any())).thenReturn(List.of());
    }

    // ── 验收场景1：测谎仪主场景 ─────────────────────────────────────────────────

    /**
     * 声明改3文件、实际多改 SecurityConfig 且删1处测试断言、答案说"已测试通过"但没调 runVerification。
     * 期望：SecurityConfig = SILENT_ADD，TEST_WEAKENED + FABRICATED_VERIFICATION，trustFlag=FABRICATED。
     */
    @Test
    void acceptance_fabricatedSession_flagsSecurityConfigAndTestWeakened() {
        // ── Setup requirement ──
        RequirementEntity req = makeReq(REQ_ID, REPO_ID, USER_ID, SESSION_ID, RUN_ID);
        when(requirementMapper.selectById(REQ_ID)).thenReturn(req);

        // ── Setup agent_run (claimed_verified=1 from answer_preview) ──
        AgentRunEntity run = makeAgentRun(RUN_ID, SESSION_ID, 1, 1, "已测试通过，功能完成。");
        when(agentRunMapper.selectById(RUN_ID)).thenReturn(run);

        // ── Setup plan (3 declared files, with proper package paths) ──
        String planJson = """
            [
              {"stepId":"step-1","title":"验证码生成","declaredOp":"MODIFY",
               "declaredFiles":["src/main/java/com/service/VerifyCodeService.java"]},
              {"stepId":"step-2","title":"登录校验","declaredOp":"MODIFY",
               "declaredFiles":["src/main/java/com/service/LoginService.java"]},
              {"stepId":"step-3","title":"持久化","declaredOp":"MODIFY",
               "declaredFiles":["src/main/java/com/mapper/VerifyCodeMapper.java"]}
            ]""";
        AgentRunPlanEntity plan = makePlan(RUN_ID, planJson);
        when(agentRunPlanMapper.selectOne(any())).thenReturn(plan);

        // ── Setup file_change_log: 3 declared files + SecurityConfig (silent) ──
        //    + test file with assertion removed
        List<FileChangeLogEntity> changes = List.of(
                makeChange(1L, SESSION_ID, "src/main/java/com/service/VerifyCodeService.java",
                        "oldA", "newA", FileChangeLogEntity.STATUS_APPLIED),
                makeChange(2L, SESSION_ID, "src/main/java/com/service/LoginService.java",
                        "oldB", "newB", FileChangeLogEntity.STATUS_APPLIED),
                makeChange(3L, SESSION_ID, "src/main/java/com/mapper/VerifyCodeMapper.java",
                        "oldC", "newC", FileChangeLogEntity.STATUS_APPLIED),
                // SecurityConfig: in a completely different package (config vs service/mapper) → SILENT_ADD
                makeChange(4L, SESSION_ID, "src/main/java/com/config/SecurityConfig.java",
                        "oldSec", "newSec", FileChangeLogEntity.STATUS_APPLIED),
                // Test file: assertion removed (each assert on separate line for correct counting)
                makeChange(5L, SESSION_ID,
                        "src/test/java/VerifyCodeServiceTest.java",
                        "@Test\nvoid t() {\n  assertEquals(1, r);\n  assertTrue(ok);\n}",
                        "@Test\nvoid t() {\n  assertEquals(1, r);\n}",
                        FileChangeLogEntity.STATUS_APPLIED)
        );
        when(fileChangeLogMapper.selectList(any())).thenReturn(changes);

        // ── No runVerification in tool_call_log ──
        when(toolCallLogMapper.selectList(any())).thenReturn(List.of());

        // ── Execute ──
        ReconciliationVO vo = service.getOrCompute(USER_ID, REPO_ID, REQ_ID);

        // ── Assert: planned=true ──
        assertThat(vo.isPlanned()).isTrue();
        assertThat(vo.isDegrade()).isFalse();

        // ── Assert: SecurityConfig is SILENT_ADD ──
        assertThat(vo.getOffPlan()).anyMatch(op ->
                op.getFilePath().contains("SecurityConfig")
                && "SILENT_ADD".equals(op.getClassification()));

        // ── Assert: TEST_WEAKENED + FABRICATED_VERIFICATION ──
        ReconciliationVO.SelfReport sr = vo.getSelfReport();
        assertThat(sr.getChecks()).anyMatch(c -> "FABRICATED_VERIFICATION".equals(c.getType()));
        assertThat(sr.getChecks()).anyMatch(c -> "TEST_WEAKENED".equals(c.getType()));

        // ── Assert: trustFlag=FABRICATED ──
        assertThat(sr.getTrustFlag()).isEqualTo("FABRICATED");
        assertThat(vo.getSummary().getTrustFlag()).isEqualTo("FABRICATED");
    }

    // ── 验收场景2：干净会话 ─────────────────────────────────────────────────────

    /**
     * 5步全落实、无计划外、跑了测试通过 → coverage=100%，无红，trustFlag=OK。
     */
    @Test
    void acceptance_cleanSession_coverage100_trustOK() {
        // ── Setup requirement ──
        RequirementEntity req = makeReq(REQ_ID, REPO_ID, USER_ID, SESSION_ID, RUN_ID);
        when(requirementMapper.selectById(REQ_ID)).thenReturn(req);

        AgentRunEntity run = makeAgentRun(RUN_ID, SESSION_ID, 1, 1, "所有功能已完成。");
        when(agentRunMapper.selectById(RUN_ID)).thenReturn(run);

        // Plan: 3 steps, each with 1 declared file
        String planJson = """
            [
              {"stepId":"step-1","title":"步骤1","declaredFiles":["src/A.java"]},
              {"stepId":"step-2","title":"步骤2","declaredFiles":["src/B.java"]},
              {"stepId":"step-3","title":"步骤3","declaredFiles":["src/C.java"]}
            ]""";
        when(agentRunPlanMapper.selectOne(any())).thenReturn(makePlan(RUN_ID, planJson));

        // All 3 declared files changed (exactly), no extras
        List<FileChangeLogEntity> changes = List.of(
                makeChange(1L, SESSION_ID, "src/A.java", "old", "new",
                        FileChangeLogEntity.STATUS_APPLIED),
                makeChange(2L, SESSION_ID, "src/B.java", "old", "new",
                        FileChangeLogEntity.STATUS_APPLIED),
                makeChange(3L, SESSION_ID, "src/C.java", "old", "new",
                        FileChangeLogEntity.STATUS_APPLIED)
        );
        when(fileChangeLogMapper.selectList(any())).thenReturn(changes);

        // runVerification with exitCode=0
        ToolCallLogEntity verLog = new ToolCallLogEntity();
        verLog.setToolName("runVerification");
        verLog.setOutputJson("{\"exitCode\":0}");
        when(toolCallLogMapper.selectList(any())).thenReturn(List.of(verLog));

        ReconciliationVO vo = service.getOrCompute(USER_ID, REPO_ID, REQ_ID);

        assertThat(vo.isPlanned()).isTrue();
        assertThat(vo.getSummary().getCoverage()).isEqualTo(1.0);
        assertThat(vo.getOffPlan()).isEmpty();
        assertThat(vo.getSelfReport().getChecks()).noneMatch(c -> "RED".equals(c.getSeverity()));
        assertThat(vo.getSummary().getTrustFlag()).isEqualTo("OK");

        // All items should be LANDED
        assertThat(vo.getItems()).allMatch(item ->
                "LANDED".equals(item.getStatus()));
    }

    // ── 验收场景3：老会话（无计划）优雅降级 ──────────────────────────────────────

    @Test
    void acceptance_oldSession_noPlan_degradesGracefully() {
        RequirementEntity req = makeReq(REQ_ID, REPO_ID, USER_ID, SESSION_ID, RUN_ID);
        when(requirementMapper.selectById(REQ_ID)).thenReturn(req);

        AgentRunEntity run = makeAgentRun(RUN_ID, SESSION_ID, 0, 0, "改了 Foo.java。");
        when(agentRunMapper.selectById(RUN_ID)).thenReturn(run);

        // No plan
        when(agentRunPlanMapper.selectOne(any())).thenReturn(null);

        List<FileChangeLogEntity> changes = List.of(
                makeChange(1L, SESSION_ID, "src/Foo.java", "old", "new",
                        FileChangeLogEntity.STATUS_APPLIED));
        when(fileChangeLogMapper.selectList(any())).thenReturn(changes);
        when(toolCallLogMapper.selectList(any())).thenReturn(List.of());

        ReconciliationVO vo = service.getOrCompute(USER_ID, REPO_ID, REQ_ID);

        assertThat(vo.isPlanned()).isFalse();
        assertThat(vo.isDegrade()).isTrue();
        assertThat(vo.getItems()).isEmpty();
        assertThat(vo.getOffPlan()).isNotEmpty(); // changes still classified
        assertThat(vo.getSelfReport()).isNotNull();
    }

    // ── 权限测试 ──────────────────────────────────────────────────────────────

    @Test
    void getOrCompute_throwsForbidden_whenNoPermission() {
        when(permissionService.checkRepoPermission(USER_ID, REPO_ID)).thenReturn(false);
        assertThatThrownBy(() -> service.getOrCompute(USER_ID, REPO_ID, REQ_ID))
                .isInstanceOf(BizException.class);
    }

    @Test
    void getOrCompute_throwsNotFound_whenReqMissing() {
        when(permissionService.checkRepoPermission(USER_ID, REPO_ID)).thenReturn(true);
        when(requirementMapper.selectById(REQ_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.getOrCompute(USER_ID, REPO_ID, REQ_ID))
                .isInstanceOf(BizException.class);
    }

    // ── 快照命中测试 ─────────────────────────────────────────────────────────

    @Test
    void getOrCompute_returnsSnapshot_whenAvailable() throws Exception {
        // Permission passes, and requirement lookup returns a valid req
        RequirementEntity req = makeReq(REQ_ID, REPO_ID, USER_ID, SESSION_ID, RUN_ID);
        when(requirementMapper.selectById(REQ_ID)).thenReturn(req);
        when(permissionService.checkRepoPermission(USER_ID, REPO_ID)).thenReturn(true);

        ReconciliationVO cachedVo = ReconciliationVO.builder()
                .planned(true).degrade(false)
                .summary(ReconciliationVO.Summary.builder().trustFlag("OK").humanLine("cached").build())
                .items(List.of()).offPlan(List.of())
                .selfReport(ReconciliationVO.SelfReport.builder().trustFlag("OK").checks(List.of()).build())
                .build();
        String json = new ObjectMapper().writeValueAsString(cachedVo);
        RequirementReconciliationEntity snapshot = new RequirementReconciliationEntity();
        snapshot.setLedgerJson(json);
        when(reconciliationMapper.selectOne(any())).thenReturn(snapshot);

        ReconciliationVO result = service.getOrCompute(USER_ID, REPO_ID, REQ_ID);
        assertThat(result.getSummary().getHumanLine()).isEqualTo("cached");
    }

    // ── 约束违规场景（B-P2）────────────────────────────────────────────────────

    @Test
    void constraintViolation_noNewDep_pomXmlModified_violationInVO() {
        RequirementEntity req = makeReq(REQ_ID, REPO_ID, USER_ID, SESSION_ID, RUN_ID);
        when(requirementMapper.selectById(REQ_ID)).thenReturn(req);

        AgentRunEntity run = makeAgentRun(RUN_ID, SESSION_ID, 1, 0, "Added new feature.");
        when(agentRunMapper.selectById(RUN_ID)).thenReturn(run);

        String planJson = """
            [{"stepId":"step-1","title":"feat","declaredFiles":["pom.xml"]}]""";
        when(agentRunPlanMapper.selectOne(any())).thenReturn(makePlan(RUN_ID, planJson));

        // pom.xml change: new content adds a dependency
        List<FileChangeLogEntity> changes = List.of(
                makeChange(1L, SESSION_ID, "pom.xml",
                        "<dependencies><dependency><groupId>spring</groupId></dependency></dependencies>",
                        "<dependencies><dependency><groupId>spring</groupId></dependency>" +
                        "<dependency><groupId>new-lib</groupId><artifactId>new-lib</artifactId></dependency>" +
                        "</dependencies>",
                        FileChangeLogEntity.STATUS_APPLIED));
        when(fileChangeLogMapper.selectList(any())).thenReturn(changes);
        when(toolCallLogMapper.selectList(any())).thenReturn(List.of());

        // Inject a NO_NEW_DEP rule via mock cache service
        ConstraintRule noNewDep = new ConstraintRule(
                ConstraintRule.NO_NEW_DEP, null, "don't add new dependencies", true,
                ConstraintRule.SEVERITY_BLOCK);
        when(agentRulesLoader.loadRules(REPO_ID)).thenReturn("don't add new dependencies");
        when(constraintRuleCacheService.loadOrParse(eq(REPO_ID), any())).thenReturn(List.of(noNewDep));

        ReconciliationVO vo = service.getOrCompute(USER_ID, REPO_ID, REQ_ID);

        assertThat(vo.getViolations()).isNotNull();
        assertThat(vo.getViolations()).hasSize(1);
        assertThat(vo.getViolations().get(0).getRuleType()).isEqualTo(ConstraintRule.NO_NEW_DEP);
        assertThat(vo.getViolations().get(0).getSeverity()).isEqualTo(ConstraintRule.SEVERITY_BLOCK);
        assertThat(vo.getSummary().getViolationCount()).isEqualTo(1);
    }

    @Test
    void constraintViolation_noRules_violationsEmpty() {
        RequirementEntity req = makeReq(REQ_ID, REPO_ID, USER_ID, SESSION_ID, RUN_ID);
        when(requirementMapper.selectById(REQ_ID)).thenReturn(req);
        when(agentRunMapper.selectById(RUN_ID)).thenReturn(makeAgentRun(RUN_ID, SESSION_ID, 0, 0, "done"));
        when(agentRunPlanMapper.selectOne(any())).thenReturn(null);
        when(fileChangeLogMapper.selectList(any())).thenReturn(List.of(
                makeChange(1L, SESSION_ID, "src/Foo.java", "old", "new", FileChangeLogEntity.STATUS_APPLIED)));
        when(toolCallLogMapper.selectList(any())).thenReturn(List.of());
        when(agentRulesLoader.loadRules(REPO_ID)).thenReturn(null); // no AGENTS.md

        ReconciliationVO vo = service.getOrCompute(USER_ID, REPO_ID, REQ_ID);

        assertThat(vo.getViolations()).isNotNull();
        assertThat(vo.getViolations()).isEmpty();
        assertThat(vo.getSummary().getViolationCount()).isEqualTo(0);
    }

    // ── 辅助构造方法 ─────────────────────────────────────────────────────────

    private RequirementEntity makeReq(Long id, Long repoId, Long userId,
                                       Long sessionId, Long runId) {
        RequirementEntity req = new RequirementEntity();
        req.setId(id);
        req.setRepoId(repoId);
        req.setUserId(userId);
        req.setSessionId(sessionId);
        req.setAgentRunId(runId);
        return req;
    }

    private AgentRunEntity makeAgentRun(Long id, Long sessionId,
                                         int claimedSuccess, int claimedVerified,
                                         String answerPreview) {
        AgentRunEntity run = new AgentRunEntity();
        run.setId(id);
        run.setSessionId(sessionId);
        run.setClaimedSuccess(claimedSuccess);
        run.setClaimedVerified(claimedVerified);
        run.setClaimEvidence(answerPreview);
        run.setAnswerPreview(answerPreview);
        return run;
    }

    private AgentRunPlanEntity makePlan(Long runId, String planJson) {
        AgentRunPlanEntity plan = new AgentRunPlanEntity();
        plan.setAgentRunId(runId);
        plan.setPlanJson(planJson);
        return plan;
    }

    private FileChangeLogEntity makeChange(Long id, Long sessionId, String filePath,
                                            String oldContent, String newContent, String status) {
        FileChangeLogEntity c = new FileChangeLogEntity();
        c.setId(id);
        c.setSessionId(sessionId);
        c.setFilePath(filePath);
        c.setOldContent(oldContent);
        c.setNewContent(newContent);
        c.setStatus(status);
        c.setOpType(FileChangeLogEntity.OP_TYPE_WRITE);
        return c;
    }
}
