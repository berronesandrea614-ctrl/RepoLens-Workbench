package com.repolens.service.impl;

import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.IndexTaskEntity;
import com.repolens.domain.entity.RepoEntity;
import com.repolens.domain.enums.RepoIndexStatus;
import com.repolens.domain.enums.TaskStatus;
import com.repolens.domain.enums.TaskType;
import com.repolens.domain.vo.BuildChunkResultVO;
import com.repolens.domain.vo.ImportRepoResultVO;
import com.repolens.domain.vo.ParseRepoResultVO;
import com.repolens.domain.vo.SyncIndexResultVO;
import com.repolens.domain.vo.VectorizeResultVO;
import com.repolens.mapper.RepoMapper;
import com.repolens.security.PermissionService;
import com.repolens.service.ChunkVectorizeService;
import com.repolens.service.CodeChunkBuildService;
import com.repolens.service.ComprehensionDebtService;
import com.repolens.service.GitRepositoryService;
import com.repolens.service.IndexTaskService;
import com.repolens.service.JavaCodeParseService;
import com.repolens.service.RepoIndexLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.UUID;

/**
 * 同步索引编排器：在当前线程内按顺序执行 import → parse → chunks → vectors 四个阶段。
 *
 * 两个使用场景：
 * 1. POST /api/repos/{id}/index/sync 一键同步索引（替代前端串行调 4 个端点）；
 * 2. repolens.index.consumer-enabled=false 时，/index/async 的内联降级路径——
 *    没有 MQ 消费者就不该发消息，否则 repo 会永远卡在 INDEXING。
 *
 * 状态机保证：任何路径都不会让 repo 停留在 INDEXING——
 * 四阶段全部成功 → INDEXED；任一阶段失败/抛异常 → FAILED（附失败阶段与原因）。
 *
 * 事务边界：整条流水线可能耗时数十秒（clone/embedding），因此本方法用
 * NOT_SUPPORTED 挂起外层事务（如 submitAsyncIndex 的 @Transactional），
 * 让各阶段服务在自己的事务里独立提交，避免长事务与提交顺序倒置。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncIndexOrchestrator {

    private final RepoMapper repoMapper;
    private final PermissionService permissionService;
    private final RepoIndexLockService repoIndexLockService;
    private final IndexTaskService indexTaskService;
    private final GitRepositoryService gitRepositoryService;
    private final JavaCodeParseService javaCodeParseService;
    private final SidecarCodeParseService sidecarCodeParseService;
    private final CodeChunkBuildService codeChunkBuildService;
    private final ChunkVectorizeService chunkVectorizeService;
    private final ComprehensionDebtService comprehensionDebtService;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SyncIndexResultVO runSyncIndex(Long repoId, Long userId) {
        RepoEntity repo = repoMapper.selectById(repoId);
        if (repo == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "Repo not found");
        }
        if (!permissionService.checkRepoPermission(userId, repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "No repo permission");
        }

        String traceId = "sync-" + UUID.randomUUID();
        RepoIndexLockService.LockResult lockResult = repoIndexLockService.tryLock(repoId, traceId);
        if (!lockResult.proceed()) {
            return SyncIndexResultVO.builder()
                    .repoId(repoId)
                    .status(TaskStatus.RUNNING)
                    .traceId(traceId)
                    .errorMsg(lockResult.message())
                    .build();
        }

        long start = System.currentTimeMillis();
        SyncIndexResultVO.SyncIndexResultVOBuilder builder = SyncIndexResultVO.builder()
                .repoId(repoId)
                .traceId(traceId);
        String stage = TaskType.CLONE_REPO.name();
        try {
            updateRepoIndexStatus(repoId, RepoIndexStatus.INDEXING);

            IndexTaskEntity cloneTask = indexTaskService.findLatestCloneTaskForImport(repoId);
            if (cloneTask == null) {
                cloneTask = indexTaskService.createManualImportCloneTask(repoId, normalizeBranch(repo.getBranchName()));
            }
            ImportRepoResultVO importResult = gitRepositoryService.importRepository(repoId, cloneTask.getId(), userId);
            builder.importResult(importResult);
            if (importResult.getStatus() == TaskStatus.FAILED) {
                return fail(builder, repoId, stage, importResult.getErrorMsg(), start);
            }

            stage = TaskType.PARSE_CODE.name();
            ParseRepoResultVO parseResult = javaCodeParseService.parseRepository(repoId, userId);
            builder.parseResult(parseResult);
            if (parseResult.getStatus() == TaskStatus.FAILED) {
                return fail(builder, repoId, stage, parseResult.getErrorMsg(), start);
            }

            // 多语言（Phase1 TS/JS）解析：必须在 Java 解析之后（Java 解析开头会清空全 repo 符号）。
            // 全程 fail-safe：sidecar 缺失/失败只记日志、返回 0，不阻断索引主流程。
            int tsSymbols = sidecarCodeParseService.parseRepository(repoId);
            if (tsSymbols > 0) {
                log.info("[SyncIndex] repo {} TS/JS 解析补充 {} 个符号", repoId, tsSymbols);
            }

            stage = TaskType.BUILD_CHUNK.name();
            BuildChunkResultVO chunkResult = codeChunkBuildService.buildChunks(repoId, userId);
            builder.chunkResult(chunkResult);
            if (chunkResult.getStatus() == TaskStatus.FAILED) {
                return fail(builder, repoId, stage, chunkResult.getErrorMsg(), start);
            }

            stage = TaskType.VECTORIZE_CHUNK.name();
            VectorizeResultVO vectorizeResult = chunkVectorizeService.vectorizeRepoChunks(repoId, userId);
            builder.vectorizeResult(vectorizeResult);
            if (vectorizeResult.getStatus() == TaskStatus.FAILED) {
                return fail(builder, repoId, stage, vectorizeResult.getErrorMsg(), start);
            }

            updateRepoIndexStatus(repoId, RepoIndexStatus.INDEXED);
            // Feature A：索引完成后异步预热理解债务物化表（失败安全）。
            comprehensionDebtService.materializeAsync(repoId, userId);
            long costMs = System.currentTimeMillis() - start;
            log.info("Sync index pipeline success, repoId={}, traceId={}, costMs={}", repoId, traceId, costMs);
            return builder.status(TaskStatus.SUCCESS).costMs(costMs).build();
        } catch (Exception ex) {
            log.warn("Sync index pipeline exception, repoId={}, stage={}, traceId={}, error={}",
                    repoId, stage, traceId, trimError(ex.getMessage()));
            return fail(builder, repoId, stage, ex.getMessage(), start);
        } finally {
            if (lockResult.lockAcquired()) {
                boolean unlocked = repoIndexLockService.unlock(repoId, traceId);
                log.info("Sync index lock release, repoId={}, traceId={}, unlocked={}", repoId, traceId, unlocked);
            }
        }
    }

    /** 阶段失败统一收口：repo 置 FAILED（绝不残留 INDEXING），返回带失败阶段的汇总。 */
    private SyncIndexResultVO fail(SyncIndexResultVO.SyncIndexResultVOBuilder builder,
                                   Long repoId,
                                   String stage,
                                   String errorMsg,
                                   long start) {
        updateRepoIndexStatus(repoId, RepoIndexStatus.FAILED);
        String error = trimError(errorMsg);
        log.warn("Sync index pipeline failed, repoId={}, stage={}, error={}", repoId, stage, error);
        return builder.status(TaskStatus.FAILED)
                .failedStage(stage)
                .errorMsg(error)
                .costMs(System.currentTimeMillis() - start)
                .build();
    }

    private void updateRepoIndexStatus(Long repoId, RepoIndexStatus status) {
        RepoEntity update = new RepoEntity();
        update.setId(repoId);
        update.setIndexStatus(status);
        repoMapper.updateById(update);
    }

    private String normalizeBranch(String branchName) {
        return StringUtils.hasText(branchName) ? branchName.trim() : "main";
    }

    private String trimError(String message) {
        if (!StringUtils.hasText(message)) {
            return "Unknown error";
        }
        String trimmed = message.trim();
        return trimmed.length() > 2000 ? trimmed.substring(0, 2000) : trimmed;
    }
}
