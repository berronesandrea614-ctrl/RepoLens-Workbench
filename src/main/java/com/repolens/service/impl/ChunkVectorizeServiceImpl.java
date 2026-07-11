package com.repolens.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.CodeChunkEntity;
import com.repolens.domain.entity.RepoEntity;
import com.repolens.domain.enums.TaskStatus;
import com.repolens.domain.enums.VectorStatus;
import com.repolens.domain.vo.VectorizeResultVO;
import com.repolens.mapper.CodeChunkMapper;
import com.repolens.mapper.RepoMapper;
import com.repolens.security.PermissionService;
import com.repolens.service.ChunkVectorizeService;
import com.repolens.service.EmbeddingService;
import com.repolens.service.MilvusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量化服务实现，对应主链路中的 “embedding + Milvus 写入” 阶段。
 *
 * 当前阶段采用“按 repo 全量重建向量”的 MVP 策略：
 * 1. MySQL 中的 code_chunk 是事实数据源；
 * 2. Milvus 只是检索索引，可以按 repo 删除后重建；
 * 3. 每次 vectors/build 都先删 repo 旧向量，再把当前 MySQL chunk 全量写回。
 *
 * 这样做的原因是：buildChunks 会先删旧 chunk 再全量重建新 chunk，
 * 如果 Milvus 中的旧向量不清理，RAG 仍可能召回已经不存在的旧代码片段。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkVectorizeServiceImpl implements ChunkVectorizeService {

    private static final int DEFAULT_BATCH_SIZE = 16;

    private final RepoMapper repoMapper;
    private final CodeChunkMapper codeChunkMapper;
    private final PermissionService permissionService;
    private final EmbeddingService embeddingService;
    private final MilvusService milvusService;
    private final PlatformTransactionManager txManager;

    @Value("${repolens.embedding.batch-size:${repolens.vectorize.batch-size:16}}")
    private int batchSize;

    @Override
    public VectorizeResultVO vectorizeRepoChunks(Long repoId, Long userId) {
        RepoEntity repo = repoMapper.selectById(repoId);
        if (repo == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "Repo not found");
        }
        if (!permissionService.checkRepoPermission(userId, repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "No repo permission");
        }

        Integer count = new TransactionTemplate(txManager).execute(s -> resetRepoChunksToPending(repoId));
        int currentChunkCount = count != null ? count : 0;
        long startTime = System.currentTimeMillis();

        try {
            // MySQL 是事实源，Milvus 是索引。全量重建前先按 repo 删除旧向量，避免旧 chunk 被脏召回。
            milvusService.deleteByRepoId(repoId);
        } catch (Exception ex) {
            new TransactionTemplate(txManager).executeWithoutResult(s -> markAllRepoChunksAsFailed(repoId));
            String errorMsg = trimError(ex.getMessage());
            log.warn("Vector rebuild delete phase failed, repoId={}, chunkCount={}, reason={}",
                    repoId, currentChunkCount, errorMsg);
            return VectorizeResultVO.builder()
                    .repoId(repoId)
                    .pendingChunkCount(currentChunkCount)
                    .embeddedChunkCount(0)
                    .failedChunkCount(currentChunkCount)
                    .status(TaskStatus.FAILED)
                    .errorMsg(errorMsg)
                    .build();
        }

        List<CodeChunkEntity> currentChunks = loadRepoChunksForRebuild(repoId);
        if (currentChunks.isEmpty()) {
            long costMs = System.currentTimeMillis() - startTime;
            log.info("Vector rebuild finished with no chunks, repoId={}, costMs={}", repoId, costMs);
            return VectorizeResultVO.builder()
                    .repoId(repoId)
                    .pendingChunkCount(0)
                    .embeddedChunkCount(0)
                    .failedChunkCount(0)
                    .status(TaskStatus.SUCCESS)
                    .errorMsg(null)
                    .build();
        }

        int batch = batchSize <= 0 ? DEFAULT_BATCH_SIZE : batchSize;
        int embeddedCount = 0;
        int failedCount = 0;
        List<String> failedBatches = new ArrayList<>();

        try {
            for (int offset = 0; offset < currentChunks.size(); offset += batch) {
                int toIndex = Math.min(offset + batch, currentChunks.size());
                List<CodeChunkEntity> currentBatch = currentChunks.subList(offset, toIndex);
                List<Long> chunkIds = currentBatch.stream()
                        .map(CodeChunkEntity::getId)
                        .toList();
                try {
                    List<String> texts = currentBatch.stream()
                            .map(CodeChunkEntity::getContent)
                            .map(content -> content == null ? "" : content)
                            .toList();
                    List<float[]> embeddings = embeddingService.embedBatch(texts);
                    milvusService.upsertCodeChunkVectors(currentBatch, embeddings);

                    // 只有当 Milvus 批量 upsert 真正成功后，当前批次才能标成 EMBEDDED。
                    new TransactionTemplate(txManager).executeWithoutResult(s -> updateVectorStatus(chunkIds, VectorStatus.EMBEDDED));
                    embeddedCount += currentBatch.size();
                } catch (Exception ex) {
                    // 单批失败只把该批次标成 FAILED，成功批次保留为 EMBEDDED，便于后续排查和重试。
                    new TransactionTemplate(txManager).executeWithoutResult(s -> updateVectorStatus(chunkIds, VectorStatus.FAILED));
                    failedCount += currentBatch.size();
                    String message = trimError(ex.getMessage());
                    failedBatches.add(message);
                    log.warn("Vector rebuild batch failed, repoId={}, from={}, to={}, reason={}",
                            repoId, offset, toIndex, message);
                }
            }
        } catch (Exception ex) {
            // 这里兜住批处理外层的异常，避免 Milvus 已删光但 MySQL 仍误保留旧的 EMBEDDED 状态。
            new TransactionTemplate(txManager).executeWithoutResult(s -> markPendingRepoChunksAsFailed(repoId));
            String errorMsg = trimError(ex.getMessage());
            log.warn("Vector rebuild failed unexpectedly, repoId={}, reason={}", repoId, errorMsg);
            return VectorizeResultVO.builder()
                    .repoId(repoId)
                    .pendingChunkCount(currentChunks.size())
                    .embeddedChunkCount(embeddedCount)
                    .failedChunkCount(currentChunks.size() - embeddedCount)
                    .status(TaskStatus.FAILED)
                    .errorMsg(errorMsg)
                    .build();
        }

        long costMs = System.currentTimeMillis() - startTime;
        TaskStatus status = failedCount > 0 ? TaskStatus.FAILED : TaskStatus.SUCCESS;
        String errorMsg = failedBatches.isEmpty() ? null : trimError(String.join(" | ", failedBatches));
        log.info("Vector rebuild completed, repoId={}, pending={}, embedded={}, failed={}, costMs={}",
                repoId, currentChunks.size(), embeddedCount, failedCount, costMs);

        return VectorizeResultVO.builder()
                .repoId(repoId)
                .pendingChunkCount(currentChunks.size())
                .embeddedChunkCount(embeddedCount)
                .failedChunkCount(failedCount)
                .status(status)
                .errorMsg(errorMsg)
                .build();
    }

    /**
     * 全量重建开始前先把当前 repo 的向量状态重置为 PENDING。
     * 这样即使后面 Milvus 删除成功但重写失败，MySQL 也不会残留“全部 EMBEDDED”的假成功状态。
     */
    private int resetRepoChunksToPending(Long repoId) {
        List<CodeChunkEntity> currentChunks = loadRepoChunksForRebuild(repoId);
        if (currentChunks.isEmpty()) {
            return 0;
        }
        codeChunkMapper.update(null, Wrappers.<CodeChunkEntity>lambdaUpdate()
                .set(CodeChunkEntity::getVectorStatus, VectorStatus.PENDING)
                .eq(CodeChunkEntity::getRepoId, repoId));
        return currentChunks.size();
    }

    private List<CodeChunkEntity> loadRepoChunksForRebuild(Long repoId) {
        return codeChunkMapper.selectList(Wrappers.<CodeChunkEntity>lambdaQuery()
                .eq(CodeChunkEntity::getRepoId, repoId)
                .orderByAsc(CodeChunkEntity::getId));
    }

    private void markAllRepoChunksAsFailed(Long repoId) {
        codeChunkMapper.update(null, Wrappers.<CodeChunkEntity>lambdaUpdate()
                .set(CodeChunkEntity::getVectorStatus, VectorStatus.FAILED)
                .eq(CodeChunkEntity::getRepoId, repoId));
    }

    /**
     * 外层异常时，仍保持“只把尚未完成的 PENDING 批次打成 FAILED”，避免覆盖已成功写回的 EMBEDDED 批次。
     */
    private void markPendingRepoChunksAsFailed(Long repoId) {
        codeChunkMapper.update(null, Wrappers.<CodeChunkEntity>lambdaUpdate()
                .set(CodeChunkEntity::getVectorStatus, VectorStatus.FAILED)
                .eq(CodeChunkEntity::getRepoId, repoId)
                .eq(CodeChunkEntity::getVectorStatus, VectorStatus.PENDING));
    }

    /**
     * 向量状态是后续调试页、重试策略和面试展示的重要事实之一，因此统一在这里做批量更新。
     */
    private void updateVectorStatus(List<Long> chunkIds, VectorStatus status) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        codeChunkMapper.update(null, Wrappers.<CodeChunkEntity>lambdaUpdate()
                .set(CodeChunkEntity::getVectorStatus, status)
                .in(CodeChunkEntity::getId, chunkIds));
    }

    private String trimError(String errorMsg) {
        if (!StringUtils.hasText(errorMsg)) {
            return "Unknown vectorize error";
        }
        String trimmed = errorMsg.trim();
        return trimmed.length() > 2000 ? trimmed.substring(0, 2000) : trimmed;
    }
}
