package com.repolens.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.AgentRunEntity;
import com.repolens.domain.entity.AgentRunPlanEntity;
import com.repolens.domain.entity.AgentRunStepEntity;
import com.repolens.domain.entity.FileChangeLogEntity;
import com.repolens.domain.entity.RequirementEntity;
import com.repolens.domain.entity.RequirementReconciliationEntity;
import com.repolens.domain.entity.ToolCallLogEntity;
import com.repolens.domain.vo.ReconciliationVO;
import com.repolens.domain.vo.ReconciliationVO.OffPlanChange;
import com.repolens.domain.vo.ReconciliationVO.PlanItemRecon;
import com.repolens.domain.vo.ReconciliationVO.SelfReport;
import com.repolens.domain.vo.ReconciliationVO.SelfReportCheck;
import com.repolens.domain.vo.ReconciliationVO.Summary;
import com.repolens.mapper.AgentRunMapper;
import com.repolens.mapper.AgentRunPlanMapper;
import com.repolens.mapper.AgentRunStepMapper;
import com.repolens.mapper.FileChangeLogMapper;
import com.repolens.mapper.RequirementMapper;
import com.repolens.mapper.RequirementReconciliationMapper;
import com.repolens.mapper.ToolCallLogMapper;
import com.repolens.security.PermissionService;
import com.repolens.service.ReconciliationService;
import com.repolens.service.impl.support.AgentRulesLoader;
import com.repolens.service.impl.support.ConstraintChecker;
import com.repolens.service.impl.support.ConstraintRule;
import com.repolens.service.impl.support.ConstraintRuleCacheService;
import com.repolens.service.impl.support.ReconciliationLogic;
import com.repolens.service.impl.support.SelfReportChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 计划 vs 实际对账服务实现（Feature B P1）。
 * 全确定性，不依赖 LLM。失败安全：所有查询包裹 try-catch，计算失败返回 degrade=true。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationServiceImpl implements ReconciliationService {

    private final RequirementMapper requirementMapper;
    private final AgentRunMapper agentRunMapper;
    private final AgentRunPlanMapper agentRunPlanMapper;
    private final AgentRunStepMapper agentRunStepMapper;
    private final FileChangeLogMapper fileChangeLogMapper;
    private final ToolCallLogMapper toolCallLogMapper;
    private final RequirementReconciliationMapper reconciliationMapper;
    private final PermissionService permissionService;
    private final ObjectMapper objectMapper;
    private final AgentRulesLoader agentRulesLoader;
    private final ConstraintRuleCacheService constraintRuleCacheService;

    @Override
    public ReconciliationVO getOrCompute(Long userId, Long repoId, Long requirementId) {
        checkPermission(userId, repoId, requirementId);
        // 惰性：有快照直接返回
        RequirementReconciliationEntity snapshot = loadSnapshot(requirementId);
        if (snapshot != null && snapshot.getLedgerJson() != null) {
            try {
                return objectMapper.readValue(snapshot.getLedgerJson(), ReconciliationVO.class);
            } catch (Exception ex) {
                log.warn("reconciliation snapshot parse failed, recomputing, reqId={}", requirementId);
            }
        }
        return doCompute(userId, repoId, requirementId, true);
    }

    @Override
    public ReconciliationVO recompute(Long userId, Long repoId, Long requirementId) {
        checkPermission(userId, repoId, requirementId);
        return doCompute(userId, repoId, requirementId, true);
    }

    // ── 主计算流程 ────────────────────────────────────────────────────────────

    private ReconciliationVO doCompute(Long userId, Long repoId, Long requirementId, boolean save) {
        try {
            return doComputeInternal(repoId, requirementId, save);
        } catch (BizException biz) {
            throw biz;
        } catch (Exception ex) {
            log.error("reconciliation compute failed, reqId={}, err={}", requirementId, ex.getMessage(), ex);
            ReconciliationVO degraded = ReconciliationVO.builder()
                    .planned(false).degrade(true)
                    .summary(Summary.builder().trustFlag("OK").humanLine("对账计算失败，请重试").build())
                    .items(List.of()).offPlan(List.of())
                    .selfReport(SelfReport.builder().trustFlag("OK").checks(List.of()).build())
                    .build();
            return degraded;
        }
    }

    private ReconciliationVO doComputeInternal(Long repoId, Long requirementId, boolean save) {
        // ── 1. 加载需求 ───────────────────────────────────────────────────────
        RequirementEntity req = requirementMapper.selectById(requirementId);
        if (req == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "Requirement not found: " + requirementId);
        }

        // ── 2. 加载 agent_run + plan ──────────────────────────────────────────
        Long runId = req.getAgentRunId();
        AgentRunEntity agentRun = null;
        AgentRunPlanEntity planEntity = null;
        if (runId != null) {
            try {
                agentRun = agentRunMapper.selectById(runId);
                planEntity = agentRunPlanMapper.selectOne(
                        Wrappers.<AgentRunPlanEntity>lambdaQuery()
                                .eq(AgentRunPlanEntity::getAgentRunId, runId));
            } catch (Exception ex) {
                log.warn("load agent_run/plan failed, reqId={}, err={}", requirementId, ex.getMessage());
            }
        }

        // ── 3. 解析计划步骤 ───────────────────────────────────────────────────
        List<PlanStepData> planSteps = parsePlanSteps(planEntity);
        boolean planned = planEntity != null && !planSteps.isEmpty();

        // ── 4. 加载实际改动 ───────────────────────────────────────────────────
        Long sessionId = req.getSessionId();
        if (sessionId == null && agentRun != null) sessionId = agentRun.getSessionId();
        List<FileChangeLogEntity> changes = loadChanges(sessionId);

        // ── 5. 加载工具步（读工具用于 MISSING_ATTEMPTED 检测）──────────────────
        List<AgentRunStepEntity> toolSteps = loadToolSteps(runId);

        // ── 6. 加载 tool_call_log（runVerification 用于自报核对）────────────────
        List<ToolCallLogEntity> toolCallLogs = loadToolCallLogs(sessionId);

        // ── 6.5. 加载约束规则（失败安全，无 AGENTS.md 时返回空列表）──────────────
        List<ConstraintRule> constraintRules = loadConstraintRules(repoId);

        // ── 7. 自报字段（优先用落库值，旧数据退化到解析 answerPreview）────────────
        boolean claimedSuccess  = agentRun != null && Integer.valueOf(1).equals(agentRun.getClaimedSuccess());
        boolean claimedVerified = agentRun != null && Integer.valueOf(1).equals(agentRun.getClaimedVerified());
        String claimEvidence    = agentRun != null ? agentRun.getClaimEvidence() : null;

        // ── 8. 对账计算 ───────────────────────────────────────────────────────
        ReconciliationVO vo;
        if (!planned) {
            vo = buildDegradeVO(claimedSuccess, claimedVerified, claimEvidence,
                    toolCallLogs, changes, constraintRules);
        } else {
            vo = buildFullVO(planSteps, changes, toolSteps, toolCallLogs,
                    claimedSuccess, claimedVerified, claimEvidence, constraintRules);
        }

        // ── 9. 存快照 ─────────────────────────────────────────────────────────
        if (save) {
            saveSnapshot(requirementId, runId, vo);
        }

        return vo;
    }

    // ── 降级 VO（无计划，仅做自报核对）──────────────────────────────────────────

    private ReconciliationVO buildDegradeVO(boolean claimedSuccess, boolean claimedVerified,
                                             String claimEvidence,
                                             List<ToolCallLogEntity> toolCallLogs,
                                             List<FileChangeLogEntity> changes,
                                             List<ConstraintRule> constraintRules) {
        List<SelfReportCheck> checks = SelfReportChecker.runChecks(
                claimedSuccess, claimedVerified, toolCallLogs, changes);
        boolean stale = SelfReportChecker.isStaleVerification(toolCallLogs);
        String trustFlag = SelfReportChecker.computeTrustFlag(checks);

        // 无计划时仍可对改动做分类（全归 SILENT_ADD）
        List<OffPlanChange> offPlan = new ArrayList<>();
        for (FileChangeLogEntity c : changes) {
            var fs = com.repolens.service.impl.support.FileContentSummarizer
                    .summarize(c.getFilePath(), c.getNewContent());
            offPlan.add(OffPlanChange.builder()
                    .filePath(c.getFilePath())
                    .classification(ReconciliationLogic.SILENT_ADD)
                    .changeId(c.getId())
                    .opType(c.getOpType())
                    .summary(fs.note())
                    .sig(fs.sig())
                    .build());
        }

        // ── 约束违规检测（B-P2）────────────────────────────────────────────────
        List<ReconciliationVO.ConstraintViolation> violations =
                ConstraintChecker.checkViolations(constraintRules, changes, toolCallLogs);

        SelfReport selfReport = SelfReport.builder()
                .claimedSuccess(claimedSuccess)
                .claimedVerified(claimedVerified)
                .claimEvidence(claimEvidence)
                .trustFlag(trustFlag)
                .staleVerification(stale)
                .checks(checks)
                .build();

        Summary summary = Summary.builder()
                .coverage(0.0)
                .fidelity(0.0)
                .offPlanCount(offPlan.size())
                .violationCount(violations.size())
                .trustFlag(trustFlag)
                .humanLine("无结构化计划（老会话），共 " + changes.size() + " 处改动，信任：" + trustFlag)
                .build();

        return ReconciliationVO.builder()
                .planned(false).degrade(true)
                .summary(summary)
                .items(List.of())
                .offPlan(offPlan)
                .violations(violations)
                .selfReport(selfReport)
                .build();
    }

    // ── 完整 VO（有计划 + 对账）─────────────────────────────────────────────────

    private ReconciliationVO buildFullVO(List<PlanStepData> planSteps,
                                          List<FileChangeLogEntity> changes,
                                          List<AgentRunStepEntity> toolSteps,
                                          List<ToolCallLogEntity> toolCallLogs,
                                          boolean claimedSuccess, boolean claimedVerified,
                                          String claimEvidence,
                                          List<ConstraintRule> constraintRules) {
        // ── 收集实际改动路径（去重）────────────────────────────────────────────
        Map<String, FileChangeLogEntity> dedupedChanges = new LinkedHashMap<>();
        for (FileChangeLogEntity c : changes) {
            if (c.getFilePath() != null) dedupedChanges.putIfAbsent(c.getFilePath(), c);
        }
        Set<String> actualFilePaths = dedupedChanges.keySet();

        // ── 收集读工具触达的文件路径 ─────────────────────────────────────────
        Set<String> toolReadFiles = collectToolReadFiles(toolSteps);

        // ── 所有声明文件（扁平）─────────────────────────────────────────────
        List<String> allDeclaredFiles = new ArrayList<>();
        for (PlanStepData ps : planSteps) {
            if (ps.declaredFiles != null) allDeclaredFiles.addAll(ps.declaredFiles);
        }

        // ── 逐步四态判定 ──────────────────────────────────────────────────────
        List<PlanItemRecon> items = new ArrayList<>();
        int totalDeclaredFiles = 0;
        int landedDeclaredFiles = 0;

        for (int i = 0; i < planSteps.size(); i++) {
            PlanStepData ps = planSteps.get(i);
            List<String> stepDeclared = ps.declaredFiles == null ? List.of() : ps.declaredFiles;

            String status = ReconciliationLogic.determinePlanItemStatus(
                    stepDeclared, actualFilePaths, toolReadFiles);

            // 计算 landedFiles / missingFiles
            List<String> landedFiles  = new ArrayList<>();
            List<String> missingFiles = new ArrayList<>();
            for (String df : stepDeclared) {
                boolean landed = actualFilePaths.stream()
                        .anyMatch(a -> ReconciliationLogic.pathMatches(a, df));
                if (landed) landedFiles.add(df);
                else         missingFiles.add(df);
            }

            totalDeclaredFiles  += stepDeclared.size();
            landedDeclaredFiles += landedFiles.size();

            String stepId = StringUtils.hasText(ps.stepId) ? ps.stepId : "step-" + (i + 1);

            items.add(PlanItemRecon.builder()
                    .stepId(stepId)
                    .title(ps.title)
                    .status(status)
                    .declaredFiles(stepDeclared)
                    .landedFiles(landedFiles)
                    .missingFiles(missingFiles)
                    .declaredOp(ps.declaredOp)
                    .build());
        }

        // ── 改动分类 ──────────────────────────────────────────────────────────
        int inPlanCount = 0;
        List<OffPlanChange> offPlan = new ArrayList<>();

        for (Map.Entry<String, FileChangeLogEntity> entry : dedupedChanges.entrySet()) {
            String filePath = entry.getKey();
            FileChangeLogEntity change = entry.getValue();
            String classification = ReconciliationLogic.classifyChange(filePath, allDeclaredFiles);
            if (ReconciliationLogic.IN_PLAN.equals(classification)) {
                inPlanCount++;
            } else {
                var fs = com.repolens.service.impl.support.FileContentSummarizer
                        .summarize(filePath, change.getNewContent());
                offPlan.add(OffPlanChange.builder()
                        .filePath(filePath)
                        .classification(classification)
                        .changeId(change.getId())
                        .opType(change.getOpType())
                        .summary(fs.note())
                        .sig(fs.sig())
                        .build());
            }
        }

        // ── 覆盖率 / 契合度 ───────────────────────────────────────────────────
        double coverage = totalDeclaredFiles > 0
                ? (double) landedDeclaredFiles / totalDeclaredFiles : 1.0;
        int totalActual = dedupedChanges.size();
        double fidelity = totalActual > 0
                ? (double) inPlanCount / totalActual : 1.0;

        // ── 自报核对 ──────────────────────────────────────────────────────────
        List<SelfReportCheck> checks = SelfReportChecker.runChecks(
                claimedSuccess, claimedVerified, toolCallLogs, changes);
        boolean stale = SelfReportChecker.isStaleVerification(toolCallLogs);
        String trustFlag = SelfReportChecker.computeTrustFlag(checks);

        SelfReport selfReport = SelfReport.builder()
                .claimedSuccess(claimedSuccess)
                .claimedVerified(claimedVerified)
                .claimEvidence(claimEvidence)
                .trustFlag(trustFlag)
                .staleVerification(stale)
                .checks(checks)
                .build();

        // ── 约束违规检测（B-P2）────────────────────────────────────────────────
        List<ReconciliationVO.ConstraintViolation> violations =
                ConstraintChecker.checkViolations(constraintRules, changes, toolCallLogs);

        // ── human line ─────────────────────────────────────────────────────────
        String humanLine = String.format("计划%d文件落实%d(%.0f%%)｜实际%d改动有%d处计划外",
                totalDeclaredFiles, landedDeclaredFiles, coverage * 100,
                totalActual, offPlan.size());

        Summary summary = Summary.builder()
                .coverage(round2(coverage))
                .fidelity(round2(fidelity))
                .offPlanCount(offPlan.size())
                .violationCount(violations.size())
                .trustFlag(trustFlag)
                .humanLine(humanLine)
                .build();

        return ReconciliationVO.builder()
                .planned(true).degrade(false)
                .summary(summary)
                .items(items)
                .offPlan(offPlan)
                .violations(violations)
                .selfReport(selfReport)
                .build();
    }

    // ── 辅助：DB 操作 ─────────────────────────────────────────────────────────

    private void checkPermission(Long userId, Long repoId, Long requirementId) {
        if (!permissionService.checkRepoPermission(userId, repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "No permission for repo " + repoId);
        }
        RequirementEntity req = requirementMapper.selectById(requirementId);
        if (req == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "Requirement not found: " + requirementId);
        }
        if (!repoId.equals(req.getRepoId())) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "Requirement does not belong to repo " + repoId);
        }
    }

    private RequirementReconciliationEntity loadSnapshot(Long requirementId) {
        try {
            return reconciliationMapper.selectOne(
                    Wrappers.<RequirementReconciliationEntity>lambdaQuery()
                            .eq(RequirementReconciliationEntity::getRequirementId, requirementId)
                            .orderByDesc(RequirementReconciliationEntity::getComputedAt)
                            .last("LIMIT 1"));
        } catch (Exception ex) {
            log.warn("load reconciliation snapshot failed, reqId={}, err={}", requirementId, ex.getMessage());
            return null;
        }
    }

    private void saveSnapshot(Long requirementId, Long agentRunId, ReconciliationVO vo) {
        try {
            String ledgerJson = objectMapper.writeValueAsString(vo);
            String trustFlag = vo.getSummary() != null ? vo.getSummary().getTrustFlag() : "OK";
            int offPlanCount = vo.getSummary() != null ? vo.getSummary().getOffPlanCount() : 0;
            double coverage  = vo.getSummary() != null ? vo.getSummary().getCoverage() : 0.0;
            double fidelity  = vo.getSummary() != null ? vo.getSummary().getFidelity() : 0.0;

            // upsert：先删旧快照，再插新快照（避免快照膨胀）
            reconciliationMapper.delete(
                    Wrappers.<RequirementReconciliationEntity>lambdaQuery()
                            .eq(RequirementReconciliationEntity::getRequirementId, requirementId));

            RequirementReconciliationEntity entity = new RequirementReconciliationEntity();
            entity.setRequirementId(requirementId);
            entity.setAgentRunId(agentRunId);
            entity.setCoverage(BigDecimal.valueOf(coverage).setScale(4, RoundingMode.HALF_UP));
            entity.setFidelity(BigDecimal.valueOf(fidelity).setScale(4, RoundingMode.HALF_UP));
            entity.setOffPlanCount(offPlanCount);
            entity.setTrustFlag(trustFlag);
            entity.setLedgerJson(ledgerJson);
            entity.setComputedAt(LocalDateTime.now());
            reconciliationMapper.insert(entity);
        } catch (Exception ex) {
            log.warn("save reconciliation snapshot failed, reqId={}, err={}", requirementId, ex.getMessage());
        }
    }

    private List<FileChangeLogEntity> loadChanges(Long sessionId) {
        if (sessionId == null) return List.of();
        try {
            return fileChangeLogMapper.selectList(
                    Wrappers.<FileChangeLogEntity>lambdaQuery()
                            .eq(FileChangeLogEntity::getSessionId, sessionId)
                            .in(FileChangeLogEntity::getStatus,
                                    FileChangeLogEntity.STATUS_APPLIED,
                                    FileChangeLogEntity.STATUS_PROPOSED,
                                    FileChangeLogEntity.STATUS_REJECTED,
                                    FileChangeLogEntity.STATUS_REVERTED));
        } catch (Exception ex) {
            log.warn("load changes failed, sessionId={}, err={}", sessionId, ex.getMessage());
            return List.of();
        }
    }

    private List<AgentRunStepEntity> loadToolSteps(Long runId) {
        if (runId == null) return List.of();
        try {
            return agentRunStepMapper.selectList(
                    Wrappers.<AgentRunStepEntity>lambdaQuery()
                            .eq(AgentRunStepEntity::getRunId, runId)
                            .eq(AgentRunStepEntity::getType, "TOOL"));
        } catch (Exception ex) {
            log.warn("load tool steps failed, runId={}, err={}", runId, ex.getMessage());
            return List.of();
        }
    }

    private List<ToolCallLogEntity> loadToolCallLogs(Long sessionId) {
        if (sessionId == null) return List.of();
        try {
            return toolCallLogMapper.selectList(
                    Wrappers.<ToolCallLogEntity>lambdaQuery()
                            .eq(ToolCallLogEntity::getSessionId, sessionId));
        } catch (Exception ex) {
            log.warn("load tool_call_log failed, sessionId={}, err={}", sessionId, ex.getMessage());
            return List.of();
        }
    }

    // ── 辅助：plan steps 解析 ─────────────────────────────────────────────────

    private List<PlanStepData> parsePlanSteps(AgentRunPlanEntity plan) {
        if (plan == null || !StringUtils.hasText(plan.getPlanJson())) return List.of();
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(
                    plan.getPlanJson(), new TypeReference<>() {});
            if (raw == null) return List.of();
            List<PlanStepData> result = new ArrayList<>();
            for (Map<String, Object> m : raw) {
                PlanStepData ps = new PlanStepData();
                ps.stepId = (String) m.get("stepId");
                ps.title  = (String) m.get("title");
                ps.declaredOp = (String) m.get("declaredOp");
                Object df = m.get("declaredFiles");
                if (df instanceof List) {
                    ps.declaredFiles = ((List<?>) df).stream()
                            .filter(Objects::nonNull)
                            .map(Object::toString)
                            .collect(Collectors.toList());
                } else {
                    ps.declaredFiles = List.of();
                }
                if (ps.title == null) ps.title = "步骤 " + (result.size() + 1);
                result.add(ps);
            }
            return result;
        } catch (Exception ex) {
            log.warn("parse plan steps failed, planId={}, err={}",
                    plan.getId(), ex.getMessage());
            return List.of();
        }
    }

    /** 从读工具步收集 target_files 路径集合。 */
    private Set<String> collectToolReadFiles(List<AgentRunStepEntity> toolSteps) {
        Set<String> result = new LinkedHashSet<>();
        for (AgentRunStepEntity ts : toolSteps) {
            String targets = ts.getTargetFiles();
            if (!StringUtils.hasText(targets)) continue;
            for (String t : targets.split(",")) {
                String p = t.trim();
                if (!p.isEmpty()) result.add(p);
            }
        }
        return result;
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /**
     * Loads and parses constraint rules from AGENTS.md for the given repo.
     * Failure-safe: returns empty list on any error or when no AGENTS.md exists.
     */
    private List<ConstraintRule> loadConstraintRules(Long repoId) {
        try {
            String rulesText = agentRulesLoader.loadRules(repoId);
            if (!StringUtils.hasText(rulesText)) return List.of();
            return constraintRuleCacheService.loadOrParse(repoId, rulesText);
        } catch (Exception ex) {
            log.warn("load constraint rules failed, repoId={}, err={}", repoId, ex.getMessage());
            return List.of();
        }
    }

    // ── 内部数据类 ────────────────────────────────────────────────────────────

    static class PlanStepData {
        String stepId;
        String title;
        String declaredOp;
        List<String> declaredFiles;
    }
}
