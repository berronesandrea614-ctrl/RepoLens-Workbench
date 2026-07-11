package com.repolens.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import com.repolens.service.MissionControlService;
import com.repolens.service.impl.support.NativeAgentLaneProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * Mission Control 聚合服务实现（H Mission Control P1）。
 *
 * <p>权限校验：checkRepoPermission 不通过直接抛 FORBIDDEN，不被 fail-safe 吞。
 * <p>fail-safe：provider 整体抛异常时，返回空 lanes/reviewQueue + summary 全 0，不崩溃。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MissionControlServiceImpl implements MissionControlService {

    private final NativeAgentLaneProvider provider;
    private final PermissionService permissionService;
    private final ComprehensionDebtFileMapper debtFileMapper;

    @Override
    public MissionControlVO overview(Long userId, Long repoId) {
        // 权限校验——FORBIDDEN 不被 fail-safe 捕获
        if (!permissionService.checkRepoPermission(userId, repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "No repo permission");
        }

        try {
            List<AgentLaneVO> lanes = provider.buildLanes(userId, repoId, 20);
            List<ReviewItemVO> reviewQueue = provider.buildReviewQueue(repoId);
            SummaryVO summary = buildSummary(lanes, repoId);
            return MissionControlVO.builder()
                    .lanes(lanes)
                    .reviewQueue(reviewQueue)
                    .summary(summary)
                    .build();
        } catch (Exception e) {
            log.warn("[MissionControlServiceImpl] overview failed, returning degraded response for repoId={}", repoId, e);
            return MissionControlVO.builder()
                    .lanes(Collections.emptyList())
                    .reviewQueue(Collections.emptyList())
                    .summary(new SummaryVO())
                    .build();
        }
    }

    // ── 私有：摘要聚合 ────────────────────────────────────────────────────────

    private SummaryVO buildSummary(List<AgentLaneVO> lanes, Long repoId) {
        int laneCount = lanes.size();
        int totalBlockRisks = 0;
        int totalWarnRisks = 0;
        int needsAttentionCount = 0;

        for (AgentLaneVO lane : lanes) {
            RiskVO risk = lane.getRisk();
            if (risk != null) {
                totalBlockRisks += risk.getBlockCount();
                totalWarnRisks += risk.getWarnCount();
            }
            if (lane.isNeedsAttention()) {
                needsAttentionCount++;
            }
        }

        // 按 repoId 查全量债务文件，内存 filter 分档计数
        List<ComprehensionDebtFileEntity> debtFiles = debtFileMapper.selectList(
                new LambdaQueryWrapper<ComprehensionDebtFileEntity>()
                        .eq(ComprehensionDebtFileEntity::getRepoId, repoId)
        );
        int redDebtFiles = (int) debtFiles.stream()
                .filter(f -> "RED".equals(f.getDebtBand()))
                .count();
        int yellowDebtFiles = (int) debtFiles.stream()
                .filter(f -> "YELLOW".equals(f.getDebtBand()))
                .count();

        return SummaryVO.builder()
                .laneCount(laneCount)
                .totalBlockRisks(totalBlockRisks)
                .totalWarnRisks(totalWarnRisks)
                .needsAttentionCount(needsAttentionCount)
                .redDebtFiles(redDebtFiles)
                .yellowDebtFiles(yellowDebtFiles)
                .build();
    }
}
