package com.repolens.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.AgentMemoryEntity;
import com.repolens.domain.entity.CodeFileEntity;
import com.repolens.domain.entity.ComprehensionDebtFileEntity;
import com.repolens.domain.entity.FileChangeLogEntity;
import com.repolens.domain.entity.RequirementSymbolEntity;
import com.repolens.domain.vo.ComprehensionDebtVO;
import com.repolens.domain.vo.DebtUnitVO;
import com.repolens.domain.vo.RepayPathVO;
import com.repolens.domain.vo.SignalBreakdownVO;
import com.repolens.mapper.CodeFileMapper;
import com.repolens.mapper.CodeSymbolMapper;
import com.repolens.mapper.ComprehensionDebtFileMapper;
import com.repolens.mapper.FileChangeLogMapper;
import com.repolens.mapper.RequirementMapper;
import com.repolens.mapper.RequirementSymbolMapper;
import com.repolens.security.PermissionService;
import com.repolens.service.AgentMemoryService;
import com.repolens.service.ComprehensionDebtService;
import com.repolens.service.impl.support.DebtScoring;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 理解债务仪表盘服务实现。
 *
 * <p>计算时机：GET 接口懒触发（物化表空/stale → 同步重算）+ 索引完成后异步预热。
 * <p>S6 降级说明：MVP 阶段无 JGit git-log，S6=0.5 固定中性值，VO 标 degraded=true。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ComprehensionDebtServiceImpl implements ComprehensionDebtService {

    private static final int  TOP_LIMIT   = 10;
    private static final int  DEFAULT_MIN = 40;

    private final PermissionService          permissionService;
    private final CodeFileMapper             codeFileMapper;
    private final CodeSymbolMapper           codeSymbolMapper;
    private final FileChangeLogMapper        fileChangeLogMapper;
    private final RequirementMapper          requirementMapper;
    private final RequirementSymbolMapper    requirementSymbolMapper;
    private final ComprehensionDebtFileMapper debtFileMapper;
    private final AgentMemoryService         agentMemoryService;
    private final ObjectMapper               objectMapper;

    // ------------------------------------------------------------------ //
    //  Public API                                                          //
    // ------------------------------------------------------------------ //

    @Override
    public ComprehensionDebtVO getDashboard(Long repoId, Long userId, int minScore) {
        checkPermission(userId, repoId);

        // 若物化表全部 stale 或为空，先触发重算
        long totalRows = debtFileMapper.selectCount(
                Wrappers.<ComprehensionDebtFileEntity>lambdaQuery()
                        .eq(ComprehensionDebtFileEntity::getRepoId, repoId));
        boolean wasStale = false;
        if (totalRows == 0) {
            computeAndMaterialize(repoId, userId);
        } else {
            long staleCount = debtFileMapper.selectCount(
                    Wrappers.<ComprehensionDebtFileEntity>lambdaQuery()
                            .eq(ComprehensionDebtFileEntity::getRepoId, repoId)
                            .eq(ComprehensionDebtFileEntity::getStale, 1));
            if (staleCount > 0) {
                wasStale = true;
                computeAndMaterialize(repoId, userId);
            }
        }

        // 读物化表
        List<ComprehensionDebtFileEntity> allRows = debtFileMapper.selectList(
                Wrappers.<ComprehensionDebtFileEntity>lambdaQuery()
                        .eq(ComprehensionDebtFileEntity::getRepoId, repoId)
                        .orderByDesc(ComprehensionDebtFileEntity::getScore));

        long redCount    = allRows.stream().filter(r -> "RED".equals(r.getDebtBand())).count();
        long yellowCount = allRows.stream().filter(r -> "YELLOW".equals(r.getDebtBand())).count();
        long greenCount  = allRows.stream().filter(r -> "GREEN".equals(r.getDebtBand())).count();

        List<DebtUnitVO> topDebt = allRows.stream()
                .filter(r -> r.getScore() >= minScore)
                .limit(TOP_LIMIT)
                .map(this::toDebtUnitVO)
                .collect(Collectors.toList());

        boolean degraded = allRows.stream().anyMatch(r -> r.getDegraded() != null && r.getDegraded() == 1);

        return ComprehensionDebtVO.builder()
                .repoId(repoId)
                .redCount((int) redCount)
                .yellowCount((int) yellowCount)
                .greenCount((int) greenCount)
                .topDebt(topDebt)
                .stale(wasStale)
                .degraded(degraded)
                .build();
    }

    @Override
    public RepayPathVO getRepayPath(Long repoId, Long fileId, Long userId) {
        checkPermission(userId, repoId);

        CodeFileEntity file = codeFileMapper.selectById(fileId);
        if (file == null || !Objects.equals(file.getRepoId(), repoId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "File not found: " + fileId);
        }

        // 1. 现成理由卡片：requirement_symbol 关联该文件
        List<String> rationales = buildRationales(repoId, file.getFilePath());

        // 2. 相关长期记忆
        String baseName = extractBaseName(file.getFilePath());
        List<AgentMemoryEntity> memories = agentMemoryService.recall(userId, repoId, baseName, 5);
        List<String> memoryTexts = memories.stream()
                .map(AgentMemoryEntity::getContent)
                .collect(Collectors.toList());

        // 当前债务分
        ComprehensionDebtFileEntity debtRow = debtFileMapper.selectOne(
                Wrappers.<ComprehensionDebtFileEntity>lambdaQuery()
                        .eq(ComprehensionDebtFileEntity::getRepoId, repoId)
                        .eq(ComprehensionDebtFileEntity::getFileId, fileId));
        int currentScore = debtRow != null ? debtRow.getScore() : 0;
        String currentBand = debtRow != null ? debtRow.getDebtBand() : "GREEN";

        // P1 占位：Claude 测验入口
        String suggestedPrompt = buildSuggestedPrompt(file.getFilePath());

        return RepayPathVO.builder()
                .fileId(fileId)
                .filePath(file.getFilePath())
                .rationales(rationales)
                .memories(memoryTexts)
                .canAskClaude(true)    // P1: Claude 测验已实装
                .suggestedPrompt(suggestedPrompt)
                .currentScore(currentScore)
                .currentBand(currentBand)
                .build();
    }

    @Override
    public void markReviewed(Long repoId, Long changeId, String reviewType,
                             Long dwellMs, Integer quizScore, Long userId) {
        checkPermission(userId, repoId);

        FileChangeLogEntity change = fileChangeLogMapper.selectById(changeId);
        if (change == null || !Objects.equals(change.getRepoId(), repoId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "Change not found: " + changeId);
        }

        // 更新复核字段（只升级，不降级：level3→不能再降到 level2）
        int existingLevel = resolveReviewLevel(change);
        int newLevel = reviewTypeToLevel(reviewType, quizScore);
        if (newLevel > existingLevel) {
            change.setReviewType(reviewType);
            change.setReviewedAt(LocalDateTime.now());
            change.setDwellMs(dwellMs);
            if (quizScore != null) change.setQuizScore(quizScore);
            fileChangeLogMapper.updateById(change);
            log.info("AUDIT mark-reviewed repoId={} changeId={} type={} score={}", repoId, changeId, reviewType, quizScore);
        }

        // 把该文件的债务标为 stale，下次 GET 重算
        markDebtStale(repoId, change.getFilePath());
    }

    @Override
    @Async
    public void materializeAsync(Long repoId, Long userId) {
        try {
            computeAndMaterialize(repoId, userId);
        } catch (Exception ex) {
            log.warn("materializeAsync failed (non-fatal), repoId={}, err={}", repoId, ex.getMessage());
        }
    }

    @Override
    public void recompute(Long repoId, Long userId) {
        checkPermission(userId, repoId);
        computeAndMaterialize(repoId, userId);
    }

    @Override
    public void markDebtStale(Long repoId, String filePath) {
        try {
            // 找到该 file_path 对应的 fileId
            CodeFileEntity file = codeFileMapper.selectOne(
                    Wrappers.<CodeFileEntity>lambdaQuery()
                            .eq(CodeFileEntity::getRepoId, repoId)
                            .eq(CodeFileEntity::getFilePath, filePath));
            if (file == null) return;

            ComprehensionDebtFileEntity stub = new ComprehensionDebtFileEntity();
            stub.setStale(1);
            debtFileMapper.update(stub,
                    Wrappers.<ComprehensionDebtFileEntity>lambdaUpdate()
                            .eq(ComprehensionDebtFileEntity::getRepoId, repoId)
                            .eq(ComprehensionDebtFileEntity::getFileId, file.getId()));
        } catch (Exception ex) {
            log.warn("markDebtStale failed (non-fatal), repoId={}, filePath={}, err={}", repoId, filePath, ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ //
    //  Core computation                                                    //
    // ------------------------------------------------------------------ //

    /**
     * 计算仓库内所有 AI 触碰过的文件的债务分，并 upsert 到物化表。
     */
    private void computeAndMaterialize(Long repoId, Long userId) {
        log.info("computeAndMaterialize start, repoId={}", repoId);

        // 所有代码文件
        List<CodeFileEntity> allFiles = codeFileMapper.selectList(
                Wrappers.<CodeFileEntity>lambdaQuery()
                        .eq(CodeFileEntity::getRepoId, repoId));

        // 所有 AI 变更日志（按 filePath 分组）
        List<FileChangeLogEntity> allChanges = fileChangeLogMapper.selectList(
                Wrappers.<FileChangeLogEntity>lambdaQuery()
                        .eq(FileChangeLogEntity::getRepoId, repoId));
        Map<String, List<FileChangeLogEntity>> changesByPath = allChanges.stream()
                .collect(Collectors.groupingBy(FileChangeLogEntity::getFilePath));

        // 14 天前时间点（S5 churn 窗口）
        LocalDateTime since14d = LocalDateTime.now().minusDays(14);

        for (CodeFileEntity file : allFiles) {
            try {
                List<FileChangeLogEntity> fileChanges = changesByPath.getOrDefault(file.getFilePath(), List.of());

                // 准入闸门：只有被 AI 改过（有 change log）的文件才计算
                if (fileChanges.isEmpty()) continue;

                computeAndUpsertOne(repoId, file, fileChanges, since14d);
            } catch (Exception ex) {
                log.warn("computeAndMaterialize file failed (skipping), repoId={}, filePath={}",
                        repoId, file.getFilePath(), ex);
            }
        }
        log.info("computeAndMaterialize done, repoId={}", repoId);
    }

    private void computeAndUpsertOne(Long repoId, CodeFileEntity file,
                                     List<FileChangeLogEntity> fileChanges,
                                     LocalDateTime since14d) {
        int lineCount = file.getLineCount() == null ? 1 : Math.max(file.getLineCount(), 1);

        // ---- S1: AI 改动行数估算 ----
        // 取所有 APPLIED/REVERTED 条目，每条估算净改动行 = |new_lines - old_lines|
        long aiChangedLines = fileChanges.stream()
                .filter(c -> FileChangeLogEntity.STATUS_APPLIED.equals(c.getStatus())
                          || FileChangeLogEntity.STATUS_REVERTED.equals(c.getStatus()))
                .mapToLong(c -> estimateDeltaLines(c))
                .sum();
        double s1 = DebtScoring.s1(aiChangedLines, lineCount);

        // 准入闸门（额外保险：S1=0 则跳过）
        if (s1 <= 0.0) return;

        // ---- S2: 复核等级 ----
        int reviewLevel = fileChanges.stream()
                .mapToInt(this::resolveReviewLevel)
                .max()
                .orElse(0);
        double s2 = DebtScoring.s2(reviewLevel);

        // ---- S3: 有无理由记录 ----
        boolean hasRationale = checkHasRationale(repoId, file.getFilePath());
        double s3 = DebtScoring.s3(hasRationale);

        // ---- S4: 认知/圈复杂度 ----
        // 查该文件所有 METHOD 符号的 max(cognitive)
        int maxCognitive = queryMaxCognitive(file.getId());
        int maxCyclomatic = queryMaxCyclomatic(file.getId());
        double s4 = DebtScoring.s4(maxCognitive);
        boolean s4Degraded = (maxCognitive == 0 && maxCyclomatic == 0);

        // ---- S5: 14 天内 churn ----
        int churn14dCount = (int) fileChanges.stream()
                .filter(c -> c.getCreatedAt() != null && c.getCreatedAt().isAfter(since14d))
                .filter(c -> FileChangeLogEntity.STATUS_APPLIED.equals(c.getStatus())
                          || FileChangeLogEntity.STATUS_REVERTED.equals(c.getStatus()))
                .count();
        double s5 = DebtScoring.s5(churn14dCount);

        // ---- S7: 测试文件启发式 ----
        boolean hasTestFile = checkHasTestFile(repoId, file.getFilePath());
        double s7 = DebtScoring.s7(hasTestFile);

        // ---- 聚合 ----
        double base      = DebtScoring.base(s1, s2, s3, s4, s5, s7);
        double amp       = DebtScoring.ampFactor(s1, reviewLevel, s4);
        int    score     = DebtScoring.finalScore(base, amp);
        String band      = DebtScoring.band(score);

        boolean degraded = s4Degraded; // S6 永远降级（MVP）

        // signals JSON
        SignalBreakdownVO breakdown = SignalBreakdownVO.builder()
                .s1AiChangedLines(aiChangedLines).s1LineCount(lineCount).s1Norm(s1)
                .s2ReviewLevel(reviewLevel).s2Norm(s2)
                .s3HasRationale(hasRationale).s3Norm(s3)
                .s4MaxCognitive(maxCognitive).s4MaxCyclomatic(maxCyclomatic).s4Norm(s4)
                .s5Churn14dCount(churn14dCount).s5Norm(s5)
                .s6Norm(DebtScoring.S6_DEGRADED).s6Degraded(true)
                .s7HasTestFile(hasTestFile).s7Norm(s7)
                .ampFactor(amp).base(base).score(score).band(band)
                .build();
        String signalsJson = toJson(breakdown);

        // Upsert
        ComprehensionDebtFileEntity existing = debtFileMapper.selectOne(
                Wrappers.<ComprehensionDebtFileEntity>lambdaQuery()
                        .eq(ComprehensionDebtFileEntity::getRepoId, repoId)
                        .eq(ComprehensionDebtFileEntity::getFileId, file.getId()));

        ComprehensionDebtFileEntity row = existing != null ? existing : new ComprehensionDebtFileEntity();
        row.setRepoId(repoId);
        row.setFileId(file.getId());
        row.setFilePath(file.getFilePath());
        row.setScore(score);
        row.setDebtBand(band);
        row.setS1AiRatio(s1);
        row.setS2Unreviewed(s2);
        row.setS3NoRationale(s3);
        row.setS4Complexity(s4);
        row.setS5Churn(s5);
        row.setS6Recency(DebtScoring.S6_DEGRADED);
        row.setS7Coverage(s7);
        row.setAmpFactor(amp);
        row.setSignalsJson(signalsJson);
        row.setDegraded(degraded ? 1 : 0);
        row.setStale(0);
        row.setComputedAt(LocalDateTime.now());

        if (existing == null) {
            row.setCreatedAt(LocalDateTime.now());
            row.setUpdatedAt(LocalDateTime.now());
            debtFileMapper.insert(row);
        } else {
            row.setUpdatedAt(LocalDateTime.now());
            debtFileMapper.updateById(row);
        }
    }

    // ------------------------------------------------------------------ //
    //  Helper methods                                                      //
    // ------------------------------------------------------------------ //

    private int resolveReviewLevel(FileChangeLogEntity c) {
        if (c.getReviewType() == null) {
            // 无复核字段：若已 APPLIED，认为至少 level1
            return FileChangeLogEntity.STATUS_APPLIED.equals(c.getStatus()) ? 1 : 0;
        }
        return switch (c.getReviewType()) {
            case "QUIZZED"     -> (c.getQuizScore() != null && c.getQuizScore() >= 65) ? 3 : 1;
            case "DIFF_VIEWED" -> 2;
            case "ACCEPTED"    -> 1;
            default            -> 0;
        };
    }

    private int reviewTypeToLevel(String reviewType, Integer quizScore) {
        return switch (reviewType != null ? reviewType : "") {
            case "QUIZZED"     -> (quizScore != null && quizScore >= 65) ? 3 : 1;
            case "DIFF_VIEWED" -> 2;
            case "ACCEPTED"    -> 1;
            default            -> 0;
        };
    }

    private long estimateDeltaLines(FileChangeLogEntity c) {
        int newLines = lineCount(c.getNewContent());
        int oldLines = lineCount(c.getOldContent());
        // 净增行数（create → 全部算新增；overwrite → 取差量 floor 0）
        return Math.max(0, newLines - oldLines);
    }

    private static int lineCount(String content) {
        if (content == null || content.isEmpty()) return 0;
        return content.split("\n", -1).length;
    }

    private boolean checkHasRationale(Long repoId, String filePath) {
        // 查 requirement_symbol 是否有关联该文件的条目
        // requirement_symbol 自身无 repo_id，通过 requirement.repo_id 关联
        // 为避免复杂 JOIN，直接查 requirement_symbol.file_path
        long cnt = requirementSymbolMapper.selectCount(
                Wrappers.<RequirementSymbolEntity>lambdaQuery()
                        .eq(RequirementSymbolEntity::getFilePath, filePath));
        return cnt > 0;
    }

    private int queryMaxCognitive(Long fileId) {
        // MyBatis-Plus 不直接支持 MAX 聚合，用 selectList + Java stream
        List<com.repolens.domain.entity.CodeSymbolEntity> symbols = codeSymbolMapper.selectList(
                Wrappers.<com.repolens.domain.entity.CodeSymbolEntity>lambdaQuery()
                        .eq(com.repolens.domain.entity.CodeSymbolEntity::getFileId, fileId)
                        .eq(com.repolens.domain.entity.CodeSymbolEntity::getSymbolType,
                                com.repolens.domain.enums.SymbolType.METHOD));
        return symbols.stream()
                .map(com.repolens.domain.entity.CodeSymbolEntity::getCognitive)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
    }

    private int queryMaxCyclomatic(Long fileId) {
        List<com.repolens.domain.entity.CodeSymbolEntity> symbols = codeSymbolMapper.selectList(
                Wrappers.<com.repolens.domain.entity.CodeSymbolEntity>lambdaQuery()
                        .eq(com.repolens.domain.entity.CodeSymbolEntity::getFileId, fileId)
                        .eq(com.repolens.domain.entity.CodeSymbolEntity::getSymbolType,
                                com.repolens.domain.enums.SymbolType.METHOD));
        return symbols.stream()
                .map(com.repolens.domain.entity.CodeSymbolEntity::getCyclomatic)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
    }

    private boolean checkHasTestFile(Long repoId, String filePath) {
        // 取不含扩展名的基名：src/main/java/.../PaymentService.java → PaymentService
        String baseName = extractBaseName(filePath);
        if (!StringUtils.hasText(baseName)) return false;

        // 查 code_file 中是否存在含 Test/test + baseName 的文件
        long cnt = codeFileMapper.selectCount(
                Wrappers.<CodeFileEntity>lambdaQuery()
                        .eq(CodeFileEntity::getRepoId, repoId)
                        .and(q -> q.like(CodeFileEntity::getFilePath, baseName + "Test")
                                   .or()
                                   .like(CodeFileEntity::getFilePath, "test_" + baseName)));
        return cnt > 0;
    }

    private List<String> buildRationales(Long repoId, String filePath) {
        List<String> rationales = new ArrayList<>();

        // 查 requirement_symbol 关联该文件的 requirement
        List<RequirementSymbolEntity> syms = requirementSymbolMapper.selectList(
                Wrappers.<RequirementSymbolEntity>lambdaQuery()
                        .eq(RequirementSymbolEntity::getFilePath, filePath));

        if (!syms.isEmpty()) {
            List<Long> reqIds = syms.stream()
                    .map(RequirementSymbolEntity::getRequirementId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .limit(3)
                    .collect(Collectors.toList());
            for (Long reqId : reqIds) {
                com.repolens.domain.entity.RequirementEntity req = requirementMapper.selectById(reqId);
                if (req != null && StringUtils.hasText(req.getTitle())) {
                    rationales.add("[需求] " + req.getTitle());
                }
            }
            if (rationales.isEmpty()) {
                rationales.add("该文件与 " + syms.size() + " 个需求关联，可在「需求流」视图查看背景。");
            }
        }
        if (rationales.isEmpty()) {
            rationales.add("暂无现成理由卡片，建议查看提交历史或让 Claude 解释（P1 功能）。");
        }
        return rationales;
    }

    private String buildSuggestedPrompt(String filePath) {
        String baseName = extractBaseName(filePath);
        return String.format(
            "请解释 %s 这段 AI 生成的代码做了什么、为什么这样设计、有哪些潜在坑，" +
            "然后出 4 道理解测验（调试/阅读/编写/概念各一道），答对 65%% 即可完成理解债务偿还。",
            baseName);
    }

    private String extractBaseName(String filePath) {
        if (filePath == null) return "";
        String name = filePath.contains("/")
                ? filePath.substring(filePath.lastIndexOf('/') + 1)
                : filePath;
        return name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
    }

    private DebtUnitVO toDebtUnitVO(ComprehensionDebtFileEntity row) {
        SignalBreakdownVO breakdown = null;
        if (StringUtils.hasText(row.getSignalsJson())) {
            try {
                breakdown = objectMapper.readValue(row.getSignalsJson(), SignalBreakdownVO.class);
            } catch (Exception ex) {
                log.debug("Failed to parse signalsJson for fileId={}", row.getFileId());
            }
        }
        if (breakdown == null) {
            breakdown = SignalBreakdownVO.builder()
                    .s1Norm(row.getS1AiRatio()).s2Norm(row.getS2Unreviewed())
                    .s3Norm(row.getS3NoRationale()).s4Norm(row.getS4Complexity())
                    .s5Norm(row.getS5Churn()).s6Norm(row.getS6Recency()).s6Degraded(true)
                    .s7Norm(row.getS7Coverage()).ampFactor(row.getAmpFactor())
                    .score(row.getScore()).band(row.getDebtBand())
                    .build();
        }
        return DebtUnitVO.builder()
                .fileId(row.getFileId())
                .filePath(row.getFilePath())
                .score(row.getScore())
                .band(row.getDebtBand())
                .lineCount(0) // lineCount 从 code_file 读，此处填 0 轻量化
                .signals(breakdown)
                .degraded(row.getDegraded() != null && row.getDegraded() == 1)
                .build();
    }

    private String toJson(Object obj) {
        try { return objectMapper.writeValueAsString(obj); }
        catch (Exception ex) { return "{}"; }
    }

    private void checkPermission(Long userId, Long repoId) {
        if (!permissionService.checkRepoPermission(userId, repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "No repo permission");
        }
    }
}
