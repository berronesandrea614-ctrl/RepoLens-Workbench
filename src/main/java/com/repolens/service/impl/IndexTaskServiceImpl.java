package com.repolens.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.repolens.domain.entity.IndexTaskEntity;
import com.repolens.domain.enums.TaskStatus;
import com.repolens.domain.enums.TaskType;
import com.repolens.domain.vo.IndexTaskVO;
import com.repolens.mapper.IndexTaskMapper;
import com.repolens.service.IndexTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class IndexTaskServiceImpl implements IndexTaskService {

    private static final int DEFAULT_MAX_RETRY = 3;

    private final IndexTaskMapper indexTaskMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IndexTaskEntity createInitCloneTask(Long repoId, String branchName) {
        String normalizedBranch = normalizeBranchName(branchName);
        String idempotentKey = repoId + ":" + normalizedBranch + ":" + TaskType.CLONE_REPO.name() + ":INIT";
        return createTaskWithIdempotentKey(repoId, TaskType.CLONE_REPO, TaskStatus.PENDING, idempotentKey);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IndexTaskEntity createReindexCloneTask(Long repoId, String branchName, String requestId) {
        String normalizedBranch = normalizeBranchName(branchName);
        String normalizedRequestId = StringUtils.hasText(requestId) ? requestId.trim() : UUID.randomUUID().toString();
        String idempotentKey = repoId + ":" + normalizedBranch + ":" + TaskType.CLONE_REPO.name()
                + ":REINDEX:" + normalizedRequestId;
        return createTaskWithIdempotentKey(repoId, TaskType.CLONE_REPO, TaskStatus.PENDING, idempotentKey);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IndexTaskEntity createManualImportCloneTask(Long repoId, String branchName) {
        String normalizedBranch = normalizeBranchName(branchName);
        String idempotentKey = repoId + ":" + normalizedBranch + ":" + TaskType.CLONE_REPO.name()
                + ":MANUAL:" + UUID.randomUUID();
        return createTaskWithIdempotentKey(repoId, TaskType.CLONE_REPO, TaskStatus.PENDING, idempotentKey);
    }

    @Override
    public String buildCommitStageIdempotentKey(Long repoId, String commitId, TaskType taskType) {
        if (!StringUtils.hasText(commitId)) {
            throw new IllegalArgumentException("commitId must not be blank");
        }
        String stageName = switch (taskType) {
            case PARSE_CODE -> "PARSE_CODE";
            case BUILD_CHUNK -> "BUILD_CHUNK";
            case EMBED_CHUNK -> "EMBED_CODE";
            case UPSERT_VECTOR -> "UPSERT_VECTOR";
            default -> taskType.name();
        };
        return repoId + ":" + commitId.trim() + ":" + stageName;
    }

    @Override
    public List<IndexTaskVO> listByRepoId(Long repoId) {
        return indexTaskMapper.selectList(Wrappers.<IndexTaskEntity>lambdaQuery()
                        .eq(IndexTaskEntity::getRepoId, repoId)
                        .orderByDesc(IndexTaskEntity::getCreatedAt))
                .stream()
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    @Override
    public IndexTaskEntity findLatestCloneTaskForImport(Long repoId) {
        return indexTaskMapper.selectOne(Wrappers.<IndexTaskEntity>lambdaQuery()
                .eq(IndexTaskEntity::getRepoId, repoId)
                .eq(IndexTaskEntity::getTaskType, TaskType.CLONE_REPO)
                .and(w -> w.eq(IndexTaskEntity::getStatus, TaskStatus.PENDING)
                        .or()
                        .eq(IndexTaskEntity::getStatus, TaskStatus.WAIT_RETRY))
                .orderByDesc(IndexTaskEntity::getId)
                .last("LIMIT 1"));
    }

    private IndexTaskEntity createTaskWithIdempotentKey(Long repoId,
                                                        TaskType taskType,
                                                        TaskStatus status,
                                                        String idempotentKey) {
        /*
         * idempotentKey is used to prevent duplicate consumption for the same pipeline stage.
         * It is NOT used to forbid users from triggering reindex multiple times.
         * Manual reindex should be repeatable, so REINDEX tasks include requestId/UUID in the key.
         */
        IndexTaskEntity existing = indexTaskMapper.selectOne(Wrappers.<IndexTaskEntity>lambdaQuery()
                .eq(IndexTaskEntity::getIdempotentKey, idempotentKey)
                .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }

        IndexTaskEntity entity = new IndexTaskEntity();
        entity.setRepoId(repoId);
        entity.setTaskType(taskType);
        entity.setStatus(status);
        entity.setRetryCount(0);
        entity.setMaxRetry(DEFAULT_MAX_RETRY);
        entity.setIdempotentKey(idempotentKey);
        indexTaskMapper.insert(entity);
        return entity;
    }

    private String normalizeBranchName(String branchName) {
        return StringUtils.hasText(branchName) ? branchName.trim() : "main";
    }

    private IndexTaskVO toVO(IndexTaskEntity entity) {
        return IndexTaskVO.builder()
                .id(entity.getId())
                .repoId(entity.getRepoId())
                .taskType(entity.getTaskType())
                .status(entity.getStatus())
                .retryCount(entity.getRetryCount())
                .maxRetry(entity.getMaxRetry())
                .idempotentKey(entity.getIdempotentKey())
                .errorMsg(entity.getErrorMsg())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
