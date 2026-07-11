package com.repolens.service.impl.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 原生 agent 泳道聚合器（H Mission Control P1）。
 *
 * <p>聚合 agent_run / agent_run_plan / file_change_log / change_risk_flag /
 * comprehension_debt_file / requirement / reconciliation 数据，组装
 * {@link AgentLaneVO} 列表与 {@link ReviewItemVO} 待审队列。
 *
 * <p>fail-safe 铁律：{@link #buildLanes} 中每条泳道独立 try-catch，
 * 单条组装失败只产出 degraded 占位泳道，不影响其它泳道或整体返回。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NativeAgentLaneProvider {

    private final AgentRunMapper agentRunMapper;
    private final AgentRunPlanMapper agentRunPlanMapper;
    private final FileChangeLogMapper fileChangeLogMapper;
    private final ChangeRiskFlagMapper changeRiskFlagMapper;
    private final ComprehensionDebtFileMapper comprehensionDebtFileMapper;
    private final RequirementMapper requirementMapper;
    private final ReconciliationService reconciliationService;

    // ── 公开 API ─────────────────────────────────────────────────────────────

    /**
     * 查询该 repo 近 {@code limit} 条 agent_run，逐条组装泳道。
     * 单条失败 → degraded 占位；整体不崩。
     */
    public List<AgentLaneVO> buildLanes(Long userId, Long repoId, int limit) {
        // 预拉取该 repo 的债务文件（RED/YELLOW）路径集合，供后续快速求交
        Set<String> debtPaths = loadDebtPaths(repoId);

        // 拉取近 limit 条 agent_run
        List<AgentRunEntity> runs = loadRuns(repoId, limit);

        List<AgentLaneVO> lanes = new ArrayList<>(runs.size());
        for (AgentRunEntity run : runs) {
            try {
                lanes.add(assembleLane(userId, repoId, run, debtPaths));
            } catch (Exception e) {
                log.warn("[NativeAgentLaneProvider] Lane assembly failed for runId={}, degraded",
                        run.getId(), e);
                AgentLaneVO degraded = new AgentLaneVO();
                degraded.setLaneId(run.getId());
                degraded.setStatus("ERROR");
                degraded.setDegraded(true);
                lanes.add(degraded);
            }
        }
        return lanes;
    }

    /**
     * 查询该 repo 所有未确认（acknowledged=0）的 change_risk_flag，
     * 组装待审队列。IRREVERSIBLE && BLOCK → interrupt=true。
     */
    public List<ReviewItemVO> buildReviewQueue(Long repoId) {
        List<ChangeRiskFlagEntity> flags;
        try {
            flags = changeRiskFlagMapper.selectList(
                    new LambdaQueryWrapper<ChangeRiskFlagEntity>()
                            .eq(ChangeRiskFlagEntity::getRepoId, repoId)
                            .eq(ChangeRiskFlagEntity::getAcknowledged, 0)
            );
        } catch (Exception e) {
            log.warn("[NativeAgentLaneProvider] buildReviewQueue failed for repoId={}", repoId, e);
            return Collections.emptyList();
        }

        List<ReviewItemVO> items = new ArrayList<>(flags.size());
        for (ChangeRiskFlagEntity flag : flags) {
            try {
                items.add(assembleReviewItem(flag));
            } catch (Exception e) {
                log.warn("[NativeAgentLaneProvider] ReviewItem assembly failed for flagId={}",
                        flag.getId(), e);
            }
        }
        return items;
    }

    // ── 私有：泳道组装 ────────────────────────────────────────────────────────

    private AgentLaneVO assembleLane(Long userId, Long repoId, AgentRunEntity run,
                                     Set<String> debtPaths) {
        Long runId = run.getId();

        // planLine
        String planLine = resolvePlanLine(runId);

        // 改动文件列表
        List<FileChangeLogEntity> changes = fileChangeLogMapper.selectList(
                new LambdaQueryWrapper<FileChangeLogEntity>()
                        .eq(FileChangeLogEntity::getAgentRunId, runId)
        );
        int changedFileCount = changes.size();
        String changesLine = buildChangesLine(changes);

        // 风险聚合
        List<Long> changeIds = changes.stream()
                .map(FileChangeLogEntity::getId)
                .collect(Collectors.toList());
        RiskVO risk = buildRisk(changeIds);

        // 偏差分析（无 requirement → null；内部失败 → null）
        DeviationVO deviation = resolveDeviation(userId, repoId, runId);

        // 债务命中数
        Set<String> changedPaths = changes.stream()
                .map(FileChangeLogEntity::getFilePath)
                .collect(Collectors.toSet());
        int debtCount = (int) changedPaths.stream().filter(debtPaths::contains).count();

        // 是否需要人工关注
        boolean needsAttention = risk.isHasIrreversibleBlock()
                || risk.getBlockCount() > 0
                || (deviation != null && !"OK".equals(deviation.getTrustFlag()))
                || (deviation != null && deviation.getMissingCount() > 0);

        AgentLaneVO lane = new AgentLaneVO();
        lane.setLaneId(runId);
        lane.setEngine("NATIVE");
        lane.setStatus(run.getStatus());
        lane.setClaimedSuccess(run.getClaimedSuccess() != null ? run.getClaimedSuccess() == 1 : null);
        lane.setClaimedVerified(run.getClaimedVerified() != null ? run.getClaimedVerified() == 1 : null);
        lane.setPlanLine(planLine);
        lane.setChangesLine(changesLine);
        lane.setChangedFileCount(changedFileCount);
        lane.setDeviation(deviation);
        lane.setDebtCount(debtCount);
        lane.setRisk(risk);
        lane.setNeedsAttention(needsAttention);
        lane.setDegraded(false);
        return lane;
    }

    private String resolvePlanLine(Long runId) {
        try {
            AgentRunPlanEntity plan = agentRunPlanMapper.selectOne(
                    new LambdaQueryWrapper<AgentRunPlanEntity>()
                            .eq(AgentRunPlanEntity::getAgentRunId, runId)
            );
            if (plan != null && plan.getApproach() != null && !plan.getApproach().isBlank()) {
                return plan.getApproach();
            }
        } catch (Exception e) {
            log.warn("[NativeAgentLaneProvider] resolvePlanLine failed for runId={}", runId, e);
        }
        return "计划未结构化";
    }

    private RiskVO buildRisk(List<Long> changeIds) {
        RiskVO risk = new RiskVO();
        if (changeIds.isEmpty()) {
            return risk;
        }
        try {
            List<ChangeRiskFlagEntity> flags = changeRiskFlagMapper.selectList(
                    new LambdaQueryWrapper<ChangeRiskFlagEntity>()
                            .in(ChangeRiskFlagEntity::getChangeId, changeIds)
            );
            int blockCount = 0, warnCount = 0;
            boolean hasIrreversibleBlock = false;
            for (ChangeRiskFlagEntity flag : flags) {
                if ("BLOCK".equals(flag.getSeverity())) {
                    blockCount++;
                    if ("IRREVERSIBLE".equals(flag.getReversibility())) {
                        hasIrreversibleBlock = true;
                    }
                } else if ("WARN".equals(flag.getSeverity())) {
                    warnCount++;
                }
            }
            risk.setBlockCount(blockCount);
            risk.setWarnCount(warnCount);
            risk.setHasIrreversibleBlock(hasIrreversibleBlock);
        } catch (Exception e) {
            log.warn("[NativeAgentLaneProvider] buildRisk failed for changeIds={}", changeIds, e);
        }
        return risk;
    }

    private DeviationVO resolveDeviation(Long userId, Long repoId, Long runId) {
        try {
            RequirementEntity req = requirementMapper.selectOne(
                    new LambdaQueryWrapper<RequirementEntity>()
                            .eq(RequirementEntity::getAgentRunId, runId)
            );
            if (req == null) {
                return null;
            }
            ReconciliationVO recon = reconciliationService.getOrCompute(userId, repoId, req.getId());
            return buildDeviationVO(recon);
        } catch (Exception e) {
            log.warn("[NativeAgentLaneProvider] resolveDeviation failed for runId={}", runId, e);
            return null;
        }
    }

    private DeviationVO buildDeviationVO(ReconciliationVO recon) {
        if (recon == null) return null;
        DeviationVO dv = new DeviationVO();
        dv.setPlanned(recon.isPlanned());

        ReconciliationVO.Summary summary = recon.getSummary();
        if (summary != null) {
            dv.setCoverage((int) Math.round(summary.getCoverage() * 100));
            dv.setTrustFlag(summary.getTrustFlag());
            dv.setOffPlanCount(summary.getOffPlanCount());
        }

        List<ReconciliationVO.PlanItemRecon> items = recon.getItems();
        if (items != null) {
            int missingCount = (int) items.stream()
                    .filter(item -> item.getStatus() != null && item.getStatus().startsWith("MISSING_"))
                    .count();
            dv.setMissingCount(missingCount);
        }
        return dv;
    }

    private ReviewItemVO assembleReviewItem(ChangeRiskFlagEntity flag) {
        String filePath = null;
        try {
            FileChangeLogEntity fcl = fileChangeLogMapper.selectById(flag.getChangeId());
            filePath = fcl != null ? fcl.getFilePath() : null;
        } catch (Exception e) {
            log.warn("[NativeAgentLaneProvider] Failed to resolve filePath for changeId={}",
                    flag.getChangeId(), e);
        }
        boolean interrupt = "IRREVERSIBLE".equals(flag.getReversibility())
                && "BLOCK".equals(flag.getSeverity());

        ReviewItemVO item = new ReviewItemVO();
        item.setChangeId(flag.getChangeId());
        item.setKind(flag.getCategory());
        item.setReversibility(flag.getReversibility());
        item.setSeverity(flag.getSeverity());
        item.setInterrupt(interrupt);
        item.setFilePath(filePath);
        item.setEvidence(flag.getEvidence());
        return item;
    }

    // ── 私有：数据加载 ────────────────────────────────────────────────────────

    private Set<String> loadDebtPaths(Long repoId) {
        try {
            List<ComprehensionDebtFileEntity> debtFiles = comprehensionDebtFileMapper.selectList(
                    new LambdaQueryWrapper<ComprehensionDebtFileEntity>()
                            .eq(ComprehensionDebtFileEntity::getRepoId, repoId)
                            .in(ComprehensionDebtFileEntity::getDebtBand, "RED", "YELLOW")
            );
            return debtFiles.stream()
                    .map(ComprehensionDebtFileEntity::getFilePath)
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            log.warn("[NativeAgentLaneProvider] loadDebtPaths failed for repoId={}", repoId, e);
            return Collections.emptySet();
        }
    }

    private List<AgentRunEntity> loadRuns(Long repoId, int limit) {
        try {
            return agentRunMapper.selectList(
                    new LambdaQueryWrapper<AgentRunEntity>()
                            .eq(AgentRunEntity::getRepoId, repoId)
                            .orderByDesc(AgentRunEntity::getCreatedAt)
                            .last("LIMIT " + limit)
            );
        } catch (Exception e) {
            log.warn("[NativeAgentLaneProvider] loadRuns failed for repoId={}", repoId, e);
            return Collections.emptyList();
        }
    }

    // ── 私有：格式化 ──────────────────────────────────────────────────────────

    private String buildChangesLine(List<FileChangeLogEntity> changes) {
        int count = changes.size();
        if (count == 0) {
            return "改动 0 文件";
        }
        List<String> examples = changes.stream()
                .limit(3)
                .map(c -> {
                    String path = c.getFilePath();
                    if (path == null || path.isBlank()) return "?";
                    int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
                    return slash >= 0 ? path.substring(slash + 1) : path;
                })
                .collect(Collectors.toList());
        String prefix = "改动 " + count + " 文件: " + String.join(", ", examples);
        return count > 3 ? prefix + " 等 " + (count - 3) + " 个" : prefix;
    }
}
