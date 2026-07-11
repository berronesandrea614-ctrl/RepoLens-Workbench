package com.repolens.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.config.InsightProperties;
import com.repolens.domain.entity.AgentRunEntity;
import com.repolens.domain.entity.AgentRunPlanEntity;
import com.repolens.domain.entity.AgentRunStepEntity;
import com.repolens.domain.entity.FileChangeLogEntity;
import com.repolens.domain.entity.RequirementEntity;
import com.repolens.domain.entity.RequirementSymbolEntity;
import com.repolens.domain.vo.FlowNodeVO;
import com.repolens.domain.vo.RequirementInsightVO;
import com.repolens.mapper.AgentRunMapper;
import com.repolens.mapper.AgentRunPlanMapper;
import com.repolens.mapper.AgentRunStepMapper;
import com.repolens.mapper.CodeFileMapper;
import com.repolens.mapper.CodeSymbolMapper;
import com.repolens.mapper.FileChangeLogMapper;
import com.repolens.mapper.RequirementMapper;
import com.repolens.mapper.RequirementSymbolMapper;
import com.repolens.security.PermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * RequirementInsightServiceImpl 单元测试。
 *
 * <p>覆盖：偏差差集计算、敏感区规则（含关闭）、flow 节点定位字段、降级形态。
 * 所有 DB 交互均 mock，无真实连接。
 */
@ExtendWith(MockitoExtension.class)
class RequirementInsightServiceImplTest {

    private static final Long USER_ID = 1L;
    private static final Long REPO_ID = 2L;
    private static final Long REQ_ID = 10L;
    private static final Long SESSION_ID = 100L;
    private static final Long RUN_ID = 50L;

    @Mock private RequirementMapper requirementMapper;
    @Mock private AgentRunMapper agentRunMapper;
    @Mock private AgentRunPlanMapper agentRunPlanMapper;
    @Mock private AgentRunStepMapper agentRunStepMapper;
    @Mock private FileChangeLogMapper fileChangeLogMapper;
    @Mock private CodeFileMapper codeFileMapper;
    @Mock private CodeSymbolMapper codeSymbolMapper;
    @Mock private PermissionService permissionService;
    @Mock private RequirementSymbolMapper requirementSymbolMapper;

    private InsightProperties insightProperties;
    private RequirementInsightServiceImpl service;

    @BeforeEach
    void setUp() {
        insightProperties = new InsightProperties();
        // 默认敏感规则：*Security*, *Auth*, *Payment*, delete*, *Config, *Migration*
        service = new RequirementInsightServiceImpl(
                requirementMapper, agentRunMapper, agentRunPlanMapper,
                agentRunStepMapper, fileChangeLogMapper, codeFileMapper,
                codeSymbolMapper, permissionService,
                new ObjectMapper(), insightProperties, requirementSymbolMapper);

        // lenient: 某些静态方法测试（globMatch/pathMatches）不经过服务，这些 stubs 不会被用到
        lenient().when(permissionService.checkRepoPermission(USER_ID, REPO_ID)).thenReturn(true);
        // 无 code_symbol 匹配（简化，避免 mapper 调用报错）
        lenient().when(codeFileMapper.selectList(any())).thenReturn(List.of());
        // 默认：没有 requirement_symbol 文件（B2 测试会覆盖）
        lenient().when(requirementSymbolMapper.selectList(any())).thenReturn(List.of());
    }

    // ── 权限 / 404 / 403 ──────────────────────────────────────────────────────────

    @Test
    void insight_requirementNotFound_throws404() {
        when(requirementMapper.selectById(REQ_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.insight(USER_ID, REPO_ID, REQ_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("not found")
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.NOT_FOUND.getCode());
    }

    @Test
    void insight_requirementBelongsToDifferentRepo_throws403() {
        RequirementEntity req = makeReq(USER_ID, 999L /* 不同 repo */, null);
        when(requirementMapper.selectById(REQ_ID)).thenReturn(req);
        assertThatThrownBy(() -> service.insight(USER_ID, REPO_ID, REQ_ID))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.FORBIDDEN.getCode());
    }

    // ── 纯问答降级 ────────────────────────────────────────────────────────────────

    @Test
    void insight_pureAsk_hasChangesFalseAndNoPanorama() {
        RequirementEntity req = makeReq(USER_ID, REPO_ID, null /* no agentRunId */);
        req.setTitle("getUserById 返回什么类型");
        when(requirementMapper.selectById(REQ_ID)).thenReturn(req);
        when(fileChangeLogMapper.selectList(any())).thenReturn(List.of());

        RequirementInsightVO vo = service.insight(USER_ID, REPO_ID, REQ_ID);

        assertThat(vo.isHasChanges()).isFalse();
        assertThat(vo.isPlanned()).isFalse();
        assertThat(vo.getDeviation()).isNull();
        assertThat(vo.getPanorama()).isNull();
        assertThat(vo.getSteps()).hasSize(1);
        assertThat(vo.getSteps().get(0).getTitle()).isEqualTo("AI 的回答依据");
        assertThat(vo.getSteps().get(0).getKind()).isEqualTo("in");
    }

    // ── 无计划 + 有改动降级 ───────────────────────────────────────────────────────

    @Test
    void insight_noplan_hasSingleOverviewStep() {
        RequirementEntity req = makeReq(USER_ID, REPO_ID, RUN_ID);
        req.setTitle("为 getUsername 加注释");
        when(requirementMapper.selectById(REQ_ID)).thenReturn(req);
        when(agentRunMapper.selectById(RUN_ID)).thenReturn(makeAgentRun(RUN_ID, SESSION_ID));
        // plan 不存在
        when(agentRunPlanMapper.selectOne(any())).thenReturn(null);
        when(agentRunStepMapper.selectList(any())).thenReturn(List.of());
        // 一条 WRITE 改动
        FileChangeLogEntity change = makeChange(1L, SESSION_ID, "src/A.java",
                FileChangeLogEntity.OP_TYPE_WRITE, null, "line1\nline2\nline3");
        when(fileChangeLogMapper.selectList(any())).thenReturn(List.of(change));

        RequirementInsightVO vo = service.insight(USER_ID, REPO_ID, REQ_ID);

        assertThat(vo.isPlanned()).isFalse();
        assertThat(vo.isHasChanges()).isTrue();
        assertThat(vo.getDeviation()).isNull();
        assertThat(vo.getSteps()).hasSize(1);
        assertThat(vo.getSteps().get(0).getTitle()).isEqualTo("改动概览");
        assertThat(vo.getSteps().get(0).getKind()).isEqualTo("in");
        assertThat(vo.getSteps().get(0).getWhy()).isNull();
    }

    // ── 偏差差集单测 ──────────────────────────────────────────────────────────────

    @Test
    void insight_deviationSet_computedCorrectly() throws Exception {
        RequirementEntity req = makeReq(USER_ID, REPO_ID, RUN_ID);
        req.setTitle("加验证码");
        when(requirementMapper.selectById(REQ_ID)).thenReturn(req);
        when(agentRunMapper.selectById(RUN_ID)).thenReturn(makeAgentRun(RUN_ID, SESSION_ID));

        // 计划：只声明 A.java
        AgentRunPlanEntity plan = new AgentRunPlanEntity();
        plan.setAgentRunId(RUN_ID);
        plan.setApproach("先做 A");
        plan.setPlanJson("[{\"title\":\"步骤1\",\"why\":\"改 A\",\"declaredFiles\":[\"A.java\"],\"insight\":\"关键\"}]");
        when(agentRunPlanMapper.selectOne(any())).thenReturn(plan);
        when(agentRunStepMapper.selectList(any())).thenReturn(List.of());

        // 实际：改了 A.java 和 B.java（B 是偏差）
        FileChangeLogEntity changeA = makeChange(1L, SESSION_ID, "src/A.java",
                FileChangeLogEntity.OP_TYPE_WRITE, "old", "new1\nnew2");
        FileChangeLogEntity changeB = makeChange(2L, SESSION_ID, "src/B.java",
                FileChangeLogEntity.OP_TYPE_CREATE, null, "created\nlines");
        when(fileChangeLogMapper.selectList(any())).thenReturn(List.of(changeA, changeB));

        RequirementInsightVO vo = service.insight(USER_ID, REPO_ID, REQ_ID);

        assertThat(vo.isPlanned()).isTrue();
        assertThat(vo.isHasChanges()).isTrue();

        // 偏差非 null，且包含 B.java
        assertThat(vo.getDeviation()).isNotNull();
        assertThat(vo.getDeviation().getFiles()).anyMatch(f -> f.contains("B.java"));
        assertThat(vo.getChips().getOffPlanCount()).isGreaterThanOrEqualTo(1);

        // 存在 kind=off 的步骤
        boolean hasOffStep = vo.getSteps().stream()
                .anyMatch(s -> "off".equals(s.getKind()) || "🚩 计划外改动".equals(s.getTitle()));
        assertThat(hasOffStep).isTrue();
    }

    @Test
    void insight_noDeviation_deviationNull() throws Exception {
        RequirementEntity req = makeReq(USER_ID, REPO_ID, RUN_ID);
        req.setTitle("改 A 文件");
        when(requirementMapper.selectById(REQ_ID)).thenReturn(req);
        when(agentRunMapper.selectById(RUN_ID)).thenReturn(makeAgentRun(RUN_ID, SESSION_ID));

        // 计划：声明 A.java
        AgentRunPlanEntity plan = new AgentRunPlanEntity();
        plan.setAgentRunId(RUN_ID);
        plan.setPlanJson("[{\"title\":\"改A\",\"why\":\"why\",\"declaredFiles\":[\"A.java\"],\"insight\":null}]");
        when(agentRunPlanMapper.selectOne(any())).thenReturn(plan);
        when(agentRunStepMapper.selectList(any())).thenReturn(List.of());

        // 实际：只改了 A.java
        FileChangeLogEntity changeA = makeChange(1L, SESSION_ID, "src/A.java",
                FileChangeLogEntity.OP_TYPE_WRITE, "old", "new");
        when(fileChangeLogMapper.selectList(any())).thenReturn(List.of(changeA));

        RequirementInsightVO vo = service.insight(USER_ID, REPO_ID, REQ_ID);

        assertThat(vo.getDeviation()).isNull();
        assertThat(vo.getChips().getOffPlanCount()).isEqualTo(0);
        // 不存在 off 步骤
        assertThat(vo.getSteps()).allMatch(s -> !"off".equals(s.getKind()));
    }

    // ── 敏感区规则单测 ────────────────────────────────────────────────────────────

    @Test
    void insight_sensitivePatternHit_stepKindRisk() throws Exception {
        RequirementEntity req = makeReq(USER_ID, REPO_ID, RUN_ID);
        req.setTitle("修改安全配置");
        when(requirementMapper.selectById(REQ_ID)).thenReturn(req);
        when(agentRunMapper.selectById(RUN_ID)).thenReturn(makeAgentRun(RUN_ID, SESSION_ID));

        // 计划：声明 SecurityConfig.java（命中 *Security*）
        AgentRunPlanEntity plan = new AgentRunPlanEntity();
        plan.setAgentRunId(RUN_ID);
        plan.setPlanJson("[{\"title\":\"改安全配置\",\"why\":\"why\","
                + "\"declaredFiles\":[\"SecurityConfig.java\"],\"insight\":null}]");
        when(agentRunPlanMapper.selectOne(any())).thenReturn(plan);
        when(agentRunStepMapper.selectList(any())).thenReturn(List.of());

        FileChangeLogEntity change = makeChange(1L, SESSION_ID, "src/SecurityConfig.java",
                FileChangeLogEntity.OP_TYPE_WRITE, "old", "new");
        when(fileChangeLogMapper.selectList(any())).thenReturn(List.of(change));

        RequirementInsightVO vo = service.insight(USER_ID, REPO_ID, REQ_ID);

        // 步骤 kind=risk
        RequirementInsightVO.InsightStep step = vo.getSteps().stream()
                .filter(s -> "改安全配置".equals(s.getTitle()))
                .findFirst()
                .orElse(null);
        assertThat(step).isNotNull();
        assertThat(step.getKind()).isEqualTo("risk");
        assertThat(step.getRiskNote()).contains("建议复审");
    }

    @Test
    void insight_sensitivePatternsDisabled_noRiskSteps() throws Exception {
        // 关闭敏感区规则（空列表）
        insightProperties.setSensitivePatterns(List.of());

        RequirementEntity req = makeReq(USER_ID, REPO_ID, RUN_ID);
        req.setTitle("修改安全配置（规则关闭）");
        when(requirementMapper.selectById(REQ_ID)).thenReturn(req);
        when(agentRunMapper.selectById(RUN_ID)).thenReturn(makeAgentRun(RUN_ID, SESSION_ID));

        AgentRunPlanEntity plan = new AgentRunPlanEntity();
        plan.setAgentRunId(RUN_ID);
        plan.setPlanJson("[{\"title\":\"改安全配置\",\"why\":\"why\","
                + "\"declaredFiles\":[\"SecurityConfig.java\"],\"insight\":null}]");
        when(agentRunPlanMapper.selectOne(any())).thenReturn(plan);
        when(agentRunStepMapper.selectList(any())).thenReturn(List.of());

        FileChangeLogEntity change = makeChange(1L, SESSION_ID, "src/SecurityConfig.java",
                FileChangeLogEntity.OP_TYPE_WRITE, "old", "new");
        when(fileChangeLogMapper.selectList(any())).thenReturn(List.of(change));

        RequirementInsightVO vo = service.insight(USER_ID, REPO_ID, REQ_ID);

        // 没有任何 risk 步骤
        assertThat(vo.getSteps()).noneMatch(s -> "risk".equals(s.getKind()));
    }

    // ── flow 节点定位字段断言 ─────────────────────────────────────────────────────

    @Test
    void insight_flowNode_hasLocatorFields() throws Exception {
        RequirementEntity req = makeReq(USER_ID, REPO_ID, RUN_ID);
        req.setTitle("改 A");
        when(requirementMapper.selectById(REQ_ID)).thenReturn(req);
        when(agentRunMapper.selectById(RUN_ID)).thenReturn(makeAgentRun(RUN_ID, SESSION_ID));

        AgentRunPlanEntity plan = new AgentRunPlanEntity();
        plan.setAgentRunId(RUN_ID);
        plan.setPlanJson("[{\"title\":\"修改A\",\"why\":\"why\","
                + "\"declaredFiles\":[\"A.java\"],\"insight\":null}]");
        when(agentRunPlanMapper.selectOne(any())).thenReturn(plan);
        when(agentRunStepMapper.selectList(any())).thenReturn(List.of());

        FileChangeLogEntity change = makeChange(77L, SESSION_ID, "src/A.java",
                FileChangeLogEntity.OP_TYPE_WRITE, "a", "b\nc");
        when(fileChangeLogMapper.selectList(any())).thenReturn(List.of(change));

        RequirementInsightVO vo = service.insight(USER_ID, REPO_ID, REQ_ID);

        // 取第一步的第一个 flow 节点
        List<Object> flow = vo.getSteps().get(0).getFlow();
        assertThat(flow).isNotEmpty();
        FlowNodeVO node = (FlowNodeVO) flow.get(0);
        assertThat(node.getNodeType()).isEqualTo("node");
        assertThat(node.getFilePath()).isEqualTo("src/A.java");
        assertThat(node.getChangeId()).isEqualTo(77L);
        assertThat(node.getCls()).isIn("mod", "new"); // WRITE op → mod
        assertThat(node.getDelta()).isNotNull(); // delta = ~N
    }

    // ── globMatch 单测 ────────────────────────────────────────────────────────────

    @Test
    void globMatch_wildcardBothEnds() {
        assertThat(RequirementInsightServiceImpl.globMatch("*Security*", "SecurityConfig")).isTrue();
        assertThat(RequirementInsightServiceImpl.globMatch("*Security*", "JdbcSecurityManager")).isTrue();
        assertThat(RequirementInsightServiceImpl.globMatch("*Security*", "UserService")).isFalse();
    }

    @Test
    void globMatch_prefixWildcard() {
        assertThat(RequirementInsightServiceImpl.globMatch("*Config", "SecurityConfig")).isTrue();
        assertThat(RequirementInsightServiceImpl.globMatch("*Config", "SecurityConfiguration")).isFalse();
        assertThat(RequirementInsightServiceImpl.globMatch("*Config", "UserConfig")).isTrue();
    }

    @Test
    void globMatch_suffixWildcard() {
        assertThat(RequirementInsightServiceImpl.globMatch("delete*", "deleteUser")).isTrue();
        assertThat(RequirementInsightServiceImpl.globMatch("delete*", "deleteBatch")).isTrue();
        assertThat(RequirementInsightServiceImpl.globMatch("delete*", "insertUser")).isFalse();
    }

    @Test
    void globMatch_caseInsensitive() {
        assertThat(RequirementInsightServiceImpl.globMatch("*Auth*", "authFilter")).isTrue();
        assertThat(RequirementInsightServiceImpl.globMatch("*auth*", "AuthFilter")).isTrue();
    }

    // ── pathMatches 单测 ──────────────────────────────────────────────────────────

    @Test
    void pathMatches_exactAndSuffixMatch() {
        assertThat(RequirementInsightServiceImpl.pathMatches(
                "src/main/java/A.java", "A.java")).isTrue();
        assertThat(RequirementInsightServiceImpl.pathMatches(
                "src/main/java/A.java", "src/main/java/A.java")).isTrue();
        assertThat(RequirementInsightServiceImpl.pathMatches(
                "src/main/java/A.java", "B.java")).isFalse();
    }

    @Test
    void pathMatches_classNameWithoutExtension() {
        assertThat(RequirementInsightServiceImpl.pathMatches(
                "src/main/java/SecurityConfig.java", "SecurityConfig")).isTrue();
    }

    // F3: boundary fix — "Config.java" must NOT match "AppConfig.java"
    @Test
    void pathMatches_noBoundaryBug_configDoesNotMatchAppConfig() {
        // Before fix: a.endsWith(d) would cause "AppConfig.java".endsWith("Config.java") = true
        assertThat(RequirementInsightServiceImpl.pathMatches(
                "src/main/java/AppConfig.java", "Config.java")).isFalse();
    }

    // F3: class-name base-name branch still works — "CaptchaService" matches its own file
    @Test
    void pathMatches_classNameMatch_captchaService() {
        assertThat(RequirementInsightServiceImpl.pathMatches(
                "src/main/java/com/example/CaptchaService.java", "CaptchaService")).isTrue();
    }

    // ── F1: chip 去重单测 ─────────────────────────────────────────────────────────

    @Test
    void insight_chipDedup_samePathCreateThenWrite_countsAsOneFile() throws Exception {
        RequirementEntity req = makeReq(USER_ID, REPO_ID, RUN_ID);
        req.setTitle("chip dedup 测试");
        when(requirementMapper.selectById(REQ_ID)).thenReturn(req);
        when(agentRunMapper.selectById(RUN_ID)).thenReturn(makeAgentRun(RUN_ID, SESSION_ID));

        // 有计划：声明 A.java
        AgentRunPlanEntity plan = new AgentRunPlanEntity();
        plan.setAgentRunId(RUN_ID);
        plan.setPlanJson("[{\"title\":\"改A\",\"why\":\"why\","
                + "\"declaredFiles\":[\"A.java\"],\"insight\":null}]");
        when(agentRunPlanMapper.selectOne(any())).thenReturn(plan);
        when(agentRunStepMapper.selectList(any())).thenReturn(List.of());

        // 同一路径 2 条记录：先 CREATE，再 WRITE
        FileChangeLogEntity create = makeChange(1L, SESSION_ID, "src/A.java",
                FileChangeLogEntity.OP_TYPE_CREATE, null, "line1\nline2");
        FileChangeLogEntity write = makeChange(2L, SESSION_ID, "src/A.java",
                FileChangeLogEntity.OP_TYPE_WRITE, "line1", "line1\nline2\nline3");
        when(fileChangeLogMapper.selectList(any())).thenReturn(List.of(create, write));

        RequirementInsightVO vo = service.insight(USER_ID, REPO_ID, REQ_ID);

        // 去重后：1 个文件，added=1, modified=0, added+modified=1
        assertThat(vo.getChips().getFilesChanged()).isEqualTo(1);
        assertThat(vo.getChips().getAdded()).isEqualTo(1);
        assertThat(vo.getChips().getModified()).isEqualTo(0);
        assertThat(vo.getChips().getAdded() + vo.getChips().getModified()).isEqualTo(1);
    }

    // ── F5: off+sensitive 优先级单测 ──────────────────────────────────────────────

    @Test
    void insight_offPlanSensitive_kindStaysOff_andRiskNoteSet() throws Exception {
        RequirementEntity req = makeReq(USER_ID, REPO_ID, RUN_ID);
        req.setTitle("安全配置计划外改动");
        when(requirementMapper.selectById(REQ_ID)).thenReturn(req);
        when(agentRunMapper.selectById(RUN_ID)).thenReturn(makeAgentRun(RUN_ID, SESSION_ID));

        // 计划：只声明 A.java
        AgentRunPlanEntity plan = new AgentRunPlanEntity();
        plan.setAgentRunId(RUN_ID);
        plan.setPlanJson("[{\"title\":\"改A\",\"why\":\"why\","
                + "\"declaredFiles\":[\"A.java\"],\"insight\":null}]");
        when(agentRunPlanMapper.selectOne(any())).thenReturn(plan);
        when(agentRunStepMapper.selectList(any())).thenReturn(List.of());

        // 实际：改了 A.java（计划内）+ SecurityConfig.java（计划外 AND 敏感）
        FileChangeLogEntity changeA = makeChange(1L, SESSION_ID, "src/A.java",
                FileChangeLogEntity.OP_TYPE_WRITE, "old", "new");
        FileChangeLogEntity changeSec = makeChange(2L, SESSION_ID, "src/SecurityConfig.java",
                FileChangeLogEntity.OP_TYPE_WRITE, "old", "new");
        when(fileChangeLogMapper.selectList(any())).thenReturn(List.of(changeA, changeSec));

        RequirementInsightVO vo = service.insight(USER_ID, REPO_ID, REQ_ID);

        // 找到计划外步骤
        RequirementInsightVO.InsightStep offStep = vo.getSteps().stream()
                .filter(s -> "🚩 计划外改动".equals(s.getTitle()))
                .findFirst()
                .orElse(null);
        assertThat(offStep).isNotNull();
        // F5: kind 必须是 "off"（不应被覆盖为 "risk"）
        assertThat(offStep.getKind()).isEqualTo("off");
        // F5: riskNote 非空（敏感区信息仍应记录）
        assertThat(offStep.getRiskNote()).isNotNull();
        assertThat(offStep.getRiskNote()).contains("建议复审");
    }

    // ── B2: external 需求 insight 路径 ────────────────────────────────────────────

    /**
     * Contract test: external requirement (source=external, requirement_symbol files, no
     * file_change_log) → insight hasChanges=true, steps contain changed files, NOT pure-QA.
     */
    @Test
    void insight_externalRequirement_hasChangesTrue_showsChangedFiles() {
        // External requirement: no agentRunId, no sessionId-based file_change_log.
        RequirementEntity req = makeExternalReq(USER_ID, REPO_ID);
        req.setTitle("校园门户开发");
        req.setApproach("分三步实现门户页面");
        when(requirementMapper.selectById(REQ_ID)).thenReturn(req);

        // requirement_symbol entries: files changed by Claude.
        // Note: fileChangeLogMapper is NOT stubbed here — for external reqs with null sessionId,
        // loadChanges(null) returns early without touching the mapper.
        RequirementSymbolEntity sym1 = makeSymbol(REQ_ID, "src/PortalController.java");
        RequirementSymbolEntity sym2 = makeSymbol(REQ_ID, "src/PortalService.java");
        when(requirementSymbolMapper.selectList(any())).thenReturn(List.of(sym1, sym2));

        RequirementInsightVO vo = service.insight(USER_ID, REPO_ID, REQ_ID);

        // Must NOT be pure-QA.
        assertThat(vo.isHasChanges()).isTrue();
        assertThat(vo.isPlanned()).isFalse();
        assertThat(vo.getFooter().getImpactNote()).doesNotContain("纯问答");

        // chips must reflect file count.
        assertThat(vo.getChips().getFilesChanged()).isEqualTo(2);

        // steps must have exactly one step titled "Claude 改动的文件".
        assertThat(vo.getSteps()).hasSize(1);
        assertThat(vo.getSteps().get(0).getTitle()).isEqualTo("Claude 改动的文件");
        assertThat(vo.getSteps().get(0).getKind()).isEqualTo("in");

        // flow must contain at least 2 nodes (one per file).
        List<Object> flow = vo.getSteps().get(0).getFlow();
        long nodeCount = flow.stream()
                .filter(n -> n instanceof FlowNodeVO)
                .count();
        assertThat(nodeCount).isEqualTo(2);

        // All nodes should have filePath populated.
        flow.stream()
                .filter(n -> n instanceof FlowNodeVO)
                .map(n -> (FlowNodeVO) n)
                .forEach(n -> assertThat(n.getFilePath()).isNotBlank());

        // Approach should be passed through.
        assertThat(vo.getApproach()).isEqualTo("分三步实现门户页面");

        // Deviation must be null (no plan).
        assertThat(vo.getDeviation()).isNull();
    }

    /**
     * Regression: code-mode requirement (no source=external) must still work normally
     * (the external path must not interfere with the existing code-mode insight path).
     */
    @Test
    void insight_codeModeRequirement_regression_unaffectedByB2() {
        RequirementEntity req = makeReq(USER_ID, REPO_ID, RUN_ID);
        req.setTitle("加验证逻辑");
        when(requirementMapper.selectById(REQ_ID)).thenReturn(req);
        when(agentRunMapper.selectById(RUN_ID)).thenReturn(makeAgentRun(RUN_ID, SESSION_ID));
        when(agentRunPlanMapper.selectOne(any())).thenReturn(null);
        when(agentRunStepMapper.selectList(any())).thenReturn(List.of());

        FileChangeLogEntity change = makeChange(1L, SESSION_ID, "src/AuthService.java",
                FileChangeLogEntity.OP_TYPE_WRITE, "old", "new1\nnew2");
        when(fileChangeLogMapper.selectList(any())).thenReturn(List.of(change));

        RequirementInsightVO vo = service.insight(USER_ID, REPO_ID, REQ_ID);

        // Code path: uses noplan VO (has changes, no plan).
        assertThat(vo.isHasChanges()).isTrue();
        assertThat(vo.isPlanned()).isFalse();
        assertThat(vo.getSteps()).hasSize(1);
        assertThat(vo.getSteps().get(0).getTitle()).isEqualTo("改动概览");
    }

    /**
     * External requirement with empty requirement_symbol → pure QA fallback (no files at all).
     */
    @Test
    void insight_externalRequirement_emptySymbols_fallsToPureQa() {
        RequirementEntity req = makeExternalReq(USER_ID, REPO_ID);
        req.setTitle("纯问题咨询");
        when(requirementMapper.selectById(REQ_ID)).thenReturn(req);
        // fileChangeLogMapper and requirementSymbolMapper are NOT stubbed explicitly here:
        // - fileChangeLogMapper: null sessionId → loadChanges returns early, no mapper call.
        // - requirementSymbolMapper: lenient setUp stub returns List.of() by default.

        RequirementInsightVO vo = service.insight(USER_ID, REPO_ID, REQ_ID);

        // No changes from either source → pure QA.
        assertThat(vo.isHasChanges()).isFalse();
        assertThat(vo.getSteps().get(0).getTitle()).isEqualTo("AI 的回答依据");
    }

    // ── 辅助建造方法 ──────────────────────────────────────────────────────────────

    private RequirementEntity makeReq(Long userId, Long repoId, Long agentRunId) {
        RequirementEntity req = new RequirementEntity();
        req.setId(REQ_ID);
        req.setUserId(userId);
        req.setRepoId(repoId);
        req.setSessionId(SESSION_ID);
        req.setAgentRunId(agentRunId);
        req.setTitle("测试需求");
        req.setStatus("SUMMARIZED");
        req.setCreatedAt(LocalDateTime.now());
        return req;
    }

    private AgentRunEntity makeAgentRun(Long runId, Long sessionId) {
        AgentRunEntity run = new AgentRunEntity();
        run.setId(runId);
        run.setSessionId(sessionId);
        run.setMode("code");
        run.setStatus("DONE");
        return run;
    }

    private FileChangeLogEntity makeChange(Long id, Long sessionId, String filePath,
                                            String opType, String oldContent, String newContent) {
        FileChangeLogEntity c = new FileChangeLogEntity();
        c.setId(id);
        c.setSessionId(sessionId);
        c.setFilePath(filePath);
        c.setOpType(opType);
        c.setOldContent(oldContent);
        c.setNewContent(newContent);
        c.setStatus(FileChangeLogEntity.STATUS_APPLIED);
        return c;
    }

    /** Build an external-source requirement (source="external", no agentRunId, no sessionId). */
    private RequirementEntity makeExternalReq(Long userId, Long repoId) {
        RequirementEntity req = new RequirementEntity();
        req.setId(REQ_ID);
        req.setUserId(userId);
        req.setRepoId(repoId);
        req.setSessionId(null);      // external: no session
        req.setAgentRunId(null);     // external: no agent run
        req.setTitle("外部需求");
        req.setSource("external");
        req.setStatus("SUMMARIZED");
        req.setCreatedAt(LocalDateTime.now());
        return req;
    }

    /** Build a RequirementSymbolEntity for a given file path (symbolId=null, as stored by external path). */
    private RequirementSymbolEntity makeSymbol(Long reqId, String filePath) {
        RequirementSymbolEntity sym = new RequirementSymbolEntity();
        sym.setRequirementId(reqId);
        sym.setSymbolId(null);
        sym.setFilePath(filePath);
        sym.setStartLine(null);
        return sym;
    }
}
