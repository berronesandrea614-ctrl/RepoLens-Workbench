package com.repolens.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.IndexTaskEntity;
import com.repolens.domain.entity.RepoEntity;
import com.repolens.domain.enums.RepoIndexStatus;
import com.repolens.domain.enums.TaskStatus;
import com.repolens.domain.enums.TaskType;
import com.repolens.domain.mq.RepoIndexMessage;
import com.repolens.domain.vo.AsyncIndexResultVO;
import com.repolens.domain.vo.BuildChunkResultVO;
import com.repolens.domain.vo.ImportRepoResultVO;
import com.repolens.domain.vo.ParseRepoResultVO;
import com.repolens.domain.vo.SyncIndexResultVO;
import com.repolens.domain.vo.VectorizeResultVO;
import com.repolens.mapper.IndexTaskMapper;
import com.repolens.mapper.RepoMapper;
import com.repolens.producer.RepoIndexMessageProducer;
import com.repolens.security.PermissionService;
import com.repolens.service.ChunkVectorizeService;
import com.repolens.service.CodeChunkBuildService;
import com.repolens.service.GitRepositoryService;
import com.repolens.service.JavaCodeParseService;
import com.repolens.service.RepoAsyncIndexService;
import com.repolens.service.RepoIndexLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 异步索引总编排服务。
 * 它把同步链路拆成四个可观察阶段：
 * CLONE_REPO -> PARSE_CODE -> BUILD_CHUNK -> VECTORIZE_CHUNK
 *
 * 这层的职责有两块：
 * 1. 维护 MySQL 中 index_task / repo.index_status 的事实状态；
 * 2. 用 Redis 做 repo 级轻量锁，防止同一个 repo 被重复点击 /index/async。
 *
 * 注意：Redis 锁不是事实状态来源。真正的阶段状态、重试次数和失败原因仍然以 MySQL 为准。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepoAsyncIndexServiceImpl implements RepoAsyncIndexService {

    private static final int DEFAULT_MAX_RETRY = 3;
    private static final int DEFAULT_QUERY_LIMIT = 100;

    private final RepoMapper repoMapper;
    private final IndexTaskMapper indexTaskMapper;
    private final PermissionService permissionService;
    private final RepoIndexLockService repoIndexLockService;
    private final RepoIndexMessageProducer producer;
    private final GitRepositoryService gitRepositoryService;
    private final JavaCodeParseService javaCodeParseService;
    private final CodeChunkBuildService codeChunkBuildService;
    private final ChunkVectorizeService chunkVectorizeService;
    private final SyncIndexOrchestrator syncIndexOrchestrator;

    @Value("${repolens.index.max-retry:3}")
    private int configuredMaxRetry;

    /**
     * 与 IndexTaskConsumer 的 @ConditionalOnProperty 同源开关。
     * 消费者关闭时绝不能再往 MQ 发消息——否则 repo 会被置 INDEXING 后永远没人消费，
     * 卡死在 INDEXING 且 Milvus 拿不到任何向量。
     */
    @Value("${repolens.index.consumer-enabled:true}")
    private boolean consumerEnabled;

    /**
     * 提交异步索引入口。
     * 顺序是：
     * 1. 先做 repo 权限校验；
     * 2. 再尝试获取 repo 级 Redis 锁，拦截重复点击；
     * 3. 拿到锁后落首个 CLONE_REPO 任务；
     * 4. 最后尝试发 MQ。
     *
     * 这样可以确保“消息没发出去，但任务事实还在 MySQL 里”，后续可由 scheduler 补偿。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AsyncIndexResultVO submitAsyncIndex(Long repoId, Long userId) {
        // 消费者关闭时降级为内联同步流水线：
        // 不落 MQ 任务、不发消息，也不由本方法置 INDEXING（状态流转全部在 orchestrator 内完成）。
        // orchestrator 方法是 NOT_SUPPORTED 事务传播，会挂起本方法的空事务，避免长事务。
        if (!consumerEnabled) {
            log.warn("repolens.index.consumer-enabled=false: MQ consumer disabled, " +
                    "running index pipeline synchronously inline, repoId={}", repoId);
            SyncIndexResultVO syncResult = syncIndexOrchestrator.runSyncIndex(repoId, userId);
            return AsyncIndexResultVO.builder()
                    .repoId(repoId)
                    .firstTaskId(syncResult.getImportResult() == null ? null : syncResult.getImportResult().getTaskId())
                    .status(syncResult.getStatus())
                    .traceId(syncResult.getTraceId())
                    .message(buildInlineSyncMessage(syncResult))
                    .lockAcquired(syncResult.getStatus() != TaskStatus.RUNNING)
                    .lockDegraded(false)
                    .build();
        }

        RepoEntity repo = loadRepoOrThrow(repoId);
        if (!permissionService.checkRepoPermission(userId, repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "No repo permission");
        }

        String traceId = UUID.randomUUID().toString();
        RepoIndexLockService.LockResult lockResult = repoIndexLockService.tryLock(repoId, traceId);
        if (!lockResult.proceed()) {
            return AsyncIndexResultVO.builder()
                    .repoId(repoId)
                    .firstTaskId(findLatestActiveTaskId(repoId))
                    .status(TaskStatus.RUNNING)
                    .traceId(traceId)
                    .message(lockResult.message())
                    .lockAcquired(false)
                    .lockDegraded(false)
                    .build();
        }

        boolean lockAcquired = lockResult.lockAcquired();
        boolean lockDegraded = lockResult.degraded();
        try {
            String branchName = normalizeBranch(repo.getBranchName());
            String idempotentKey = buildAsyncIdempotentKey(repoId, branchName, TaskType.CLONE_REPO, traceId);

            IndexTaskEntity firstTask = createOrGetTask(repoId, TaskType.CLONE_REPO, idempotentKey);
            updateRepoIndexStatus(repoId, RepoIndexStatus.INDEXING);

            RepoIndexMessage firstMessage = buildMessage(repoId, firstTask.getId(), userId, firstTask, traceId);
            TaskStatus resultStatus = firstTask.getStatus();
            String resultMessage = lockDegraded
                    ? "index lock unavailable, task submitted without lock"
                    : "async index task submitted";

            // Defer MQ send to afterCommit: ensures task record is committed before consumer can see it.
            // When not in an active transaction (e.g., unit tests), send immediately.
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
                final RepoIndexMessage msgToSend = firstMessage;
                final Long taskIdForLog = firstTask.getId();
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            try {
                                producer.sendIndexMessage(msgToSend);
                            } catch (Exception ex) {
                                // Task stays PENDING; retry scheduler will pick it up.
                                log.warn("afterCommit MQ send failed, repoId={}, taskId={}, reason={}",
                                        repoId, taskIdForLog, trimError(ex.getMessage()));
                            }
                        }
                    });
            } else {
                // No active transaction synchronization (unit tests, sync path) — send immediately.
                try {
                    producer.sendIndexMessage(firstMessage);
                } catch (Exception ex) {
                    resultStatus = markDispatchFailure(firstTask.getId(), repoId, ex.getMessage());
                    resultMessage = "MQ send failed, task persisted for retry";
                    log.warn("Submit async index send failed, repoId={}, taskId={}, traceId={}, reason={}",
                            repoId, firstTask.getId(), traceId, trimError(ex.getMessage()));
                }
            }

            return AsyncIndexResultVO.builder()
                    .repoId(repoId)
                    .firstTaskId(firstTask.getId())
                    .status(resultStatus)
                    .traceId(traceId)
                    .message(resultMessage)
                    .lockAcquired(lockAcquired)
                    .lockDegraded(lockDegraded)
                    .build();
        } catch (RuntimeException ex) {
            // 只有在锁真的拿到了，但任务事实还没稳定落库时，才需要主动释放锁。
            if (lockAcquired) {
                repoIndexLockService.unlock(repoId, traceId);
            }
            throw ex;
        }
    }

    @Override
    public SyncIndexResultVO runSyncIndex(Long repoId, Long userId) {
        return syncIndexOrchestrator.runSyncIndex(repoId, userId);
    }

    private String buildInlineSyncMessage(SyncIndexResultVO syncResult) {
        if (syncResult.getStatus() == TaskStatus.SUCCESS) {
            return "async consumer disabled, index pipeline executed synchronously";
        }
        if (syncResult.getStatus() == TaskStatus.RUNNING) {
            return syncResult.getErrorMsg();
        }
        return "sync index failed at " + syncResult.getFailedStage() + ": " + syncResult.getErrorMsg();
    }

    /**
     * 处理单个阶段消息。
     * 这里只负责状态机切换与阶段调度，具体 repo 导入 / 解析 / 分块 / 向量化逻辑仍复用同步服务实现。
     */
    @Override
    public void handleIndexMessage(RepoIndexMessage message) {
        if (message == null || message.getTaskId() == null || message.getRepoId() == null || message.getTaskType() == null) {
            log.warn("Ignore invalid index message: {}", message);
            return;
        }

        IndexTaskEntity task = indexTaskMapper.selectById(message.getTaskId());
        if (task == null) {
            log.warn("Ignore index message because task missing, taskId={}, repoId={}", message.getTaskId(), message.getRepoId());
            return;
        }
        if (!Objects.equals(task.getRepoId(), message.getRepoId())) {
            log.warn("Ignore index message because repo mismatch, messageRepoId={}, taskRepoId={}, taskId={}",
                    message.getRepoId(), task.getRepoId(), task.getId());
            return;
        }
        if (StringUtils.hasText(message.getIdempotentKey()) && !Objects.equals(message.getIdempotentKey(), task.getIdempotentKey())) {
            log.warn("Ignore stale message because idempotentKey mismatch, taskId={}, expected={}, actual={}",
                    task.getId(), task.getIdempotentKey(), message.getIdempotentKey());
            return;
        }

        TaskStatus currentStatus = task.getStatus();
        if (currentStatus == TaskStatus.SUCCESS) {
            log.info("Skip duplicate message because task already SUCCESS, taskId={}", task.getId());
            return;
        }
        if (currentStatus == TaskStatus.FAILED) {
            log.info("Skip message because task already FAILED, taskId={}", task.getId());
            return;
        }
        if (currentStatus == TaskStatus.RUNNING) {
            log.info("Skip message because task is RUNNING, taskId={}", task.getId());
            return;
        }
        if (currentStatus != TaskStatus.PENDING && currentStatus != TaskStatus.WAIT_RETRY) {
            log.info("Skip message because task status unsupported, taskId={}, status={}", task.getId(), currentStatus);
            return;
        }

        if (!markTaskRunning(task.getId())) {
            log.info("Skip message because task running lock failed, taskId={}", task.getId());
            return;
        }

        long start = System.currentTimeMillis();
        try {
            executeStage(message, task);
            markTaskSuccess(task.getId());
            dispatchNextStage(message, task);
            log.info("Async index stage success, taskId={}, repoId={}, taskType={}, traceId={}, costMs={}",
                    task.getId(), task.getRepoId(), task.getTaskType(), resolveTraceId(message, task),
                    System.currentTimeMillis() - start);
        } catch (Exception ex) {
            onStageFailed(task, ex);
        }
    }

    @Override
    public List<IndexTaskEntity> findRetryTasks(LocalDateTime beforeTime, int limit) {
        return queryTasksByStatusBefore(TaskStatus.WAIT_RETRY, beforeTime, limit);
    }

    @Override
    public List<IndexTaskEntity> findPendingTimeoutTasks(LocalDateTime beforeTime, int limit) {
        return queryTasksByStatusBefore(TaskStatus.PENDING, beforeTime, limit);
    }

    @Override
    public List<IndexTaskEntity> findRunningTimeoutTasks(LocalDateTime beforeTime, int limit) {
        return queryTasksByStatusBefore(TaskStatus.RUNNING, beforeTime, limit);
    }

    /**
     * 重新投递可重试任务。
     * 如果任务已经超出最大重试次数，会直接转终态 FAILED 并释放 repo 锁。
     */
    @Override
    public void requeueTask(IndexTaskEntity task, String reason) {
        if (task == null || task.getId() == null) {
            return;
        }
        IndexTaskEntity latest = indexTaskMapper.selectById(task.getId());
        if (latest == null || latest.getStatus() == TaskStatus.SUCCESS || latest.getStatus() == TaskStatus.FAILED) {
            return;
        }

        int retryCount = safeInt(latest.getRetryCount());
        int maxRetry = resolveMaxRetry(latest.getMaxRetry());
        if (retryCount >= maxRetry) {
            markTaskFailed(latest, "Exceeded max retry before requeue");
            return;
        }

        RepoEntity repo = repoMapper.selectById(latest.getRepoId());
        Long dispatchUserId = resolveDispatchUserId(null, repo);
        String traceId = resolveTraceId(latest.getIdempotentKey(), latest.getId());
        RepoIndexMessage message = buildMessage(latest.getRepoId(), latest.getId(), dispatchUserId, latest, traceId);
        try {
            producer.sendIndexMessage(message);
            updateTaskToPending(latest.getId(), "Requeued by scheduler: " + reason);
            log.info("Requeue task sent, taskId={}, repoId={}, taskType={}, reason={}",
                    latest.getId(), latest.getRepoId(), latest.getTaskType(), reason);
        } catch (Exception ex) {
            markDispatchFailure(latest.getId(), latest.getRepoId(), ex.getMessage());
            log.warn("Requeue task send failed, taskId={}, reason={}, error={}",
                    latest.getId(), reason, trimError(ex.getMessage()));
        }
    }

    /**
     * RUNNING 超时通常意味着消费者中断或进程异常退出。
     * 这里会把任务拉回 WAIT_RETRY；如果已经到达最终重试上限，则直接 FAILED 并释放锁。
     */
    @Override
    public void transferRunningTimeoutTask(IndexTaskEntity task) {
        if (task == null || task.getId() == null) {
            return;
        }
        IndexTaskEntity latest = indexTaskMapper.selectById(task.getId());
        if (latest == null || latest.getStatus() != TaskStatus.RUNNING) {
            return;
        }
        int nextRetry = safeInt(latest.getRetryCount()) + 1;
        int maxRetry = resolveMaxRetry(latest.getMaxRetry());
        if (nextRetry >= maxRetry) {
            markTaskFailed(latest, "Running timeout exceeded max retry");
            return;
        }

        IndexTaskEntity update = new IndexTaskEntity();
        update.setId(latest.getId());
        update.setStatus(TaskStatus.WAIT_RETRY);
        update.setRetryCount(nextRetry);
        update.setErrorMsg("Running timeout, waiting retry");
        indexTaskMapper.updateById(update);
    }

    private List<IndexTaskEntity> queryTasksByStatusBefore(TaskStatus status, LocalDateTime beforeTime, int limit) {
        int queryLimit = limit <= 0 ? DEFAULT_QUERY_LIMIT : limit;
        return indexTaskMapper.selectList(Wrappers.<IndexTaskEntity>lambdaQuery()
                .eq(IndexTaskEntity::getStatus, status)
                .le(IndexTaskEntity::getUpdatedAt, beforeTime)
                .orderByAsc(IndexTaskEntity::getUpdatedAt)
                .last("LIMIT " + queryLimit));
    }

    private RepoEntity loadRepoOrThrow(Long repoId) {
        RepoEntity repo = repoMapper.selectById(repoId);
        if (repo == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "Repo not found");
        }
        return repo;
    }

    private void executeStage(RepoIndexMessage message, IndexTaskEntity task) {
        updateRepoIndexStatus(task.getRepoId(), RepoIndexStatus.INDEXING);
        TaskType taskType = task.getTaskType();

        switch (taskType) {
            case CLONE_REPO -> {
                ImportRepoResultVO result = gitRepositoryService.importRepository(
                        message.getRepoId(), message.getTaskId(), resolveDispatchUserId(message.getUserId(), null));
                if (result.getStatus() == TaskStatus.FAILED) {
                    throw new BizException(ErrorCode.EXTERNAL_SERVICE_ERROR, trimError(result.getErrorMsg()));
                }
            }
            case PARSE_CODE -> {
                ParseRepoResultVO result = javaCodeParseService.parseRepository(
                        message.getRepoId(), resolveDispatchUserId(message.getUserId(), null));
                if (result.getStatus() == TaskStatus.FAILED) {
                    throw new BizException(ErrorCode.EXTERNAL_SERVICE_ERROR, trimError(result.getErrorMsg()));
                }
            }
            case BUILD_CHUNK -> {
                BuildChunkResultVO result = codeChunkBuildService.buildChunks(
                        message.getRepoId(), resolveDispatchUserId(message.getUserId(), null));
                if (result.getStatus() == TaskStatus.FAILED) {
                    throw new BizException(ErrorCode.EXTERNAL_SERVICE_ERROR, trimError(result.getErrorMsg()));
                }
            }
            case VECTORIZE_CHUNK -> {
                VectorizeResultVO result = chunkVectorizeService.vectorizeRepoChunks(
                        message.getRepoId(), resolveDispatchUserId(message.getUserId(), null));
                if (result.getStatus() == TaskStatus.FAILED) {
                    throw new BizException(ErrorCode.EXTERNAL_SERVICE_ERROR, trimError(result.getErrorMsg()));
                }
            }
            default -> throw new BizException(ErrorCode.BAD_REQUEST, "Unsupported async task type: " + taskType);
        }
    }

    private void dispatchNextStage(RepoIndexMessage currentMessage, IndexTaskEntity currentTask) {
        TaskType nextType = nextTaskType(currentTask.getTaskType());
        if (nextType == null) {
            updateRepoIndexStatus(currentTask.getRepoId(), RepoIndexStatus.INDEXED);
            releaseRepoIndexLock(currentTask.getRepoId(), resolveTraceId(currentMessage, currentTask), "async-index-finished");
            return;
        }

        RepoEntity repo = loadRepoOrThrow(currentTask.getRepoId());
        String branchName = normalizeBranch(repo.getBranchName());
        String traceId = resolveTraceId(currentMessage, currentTask);
        String idempotentKey = buildAsyncIdempotentKey(currentTask.getRepoId(), branchName, nextType, traceId);
        IndexTaskEntity nextTask = createOrGetTask(currentTask.getRepoId(), nextType, idempotentKey);

        if (nextTask.getStatus() == TaskStatus.SUCCESS || nextTask.getStatus() == TaskStatus.RUNNING) {
            return;
        }

        Long dispatchUserId = resolveDispatchUserId(currentMessage.getUserId(), repo);
        RepoIndexMessage nextMessage = buildMessage(currentTask.getRepoId(), nextTask.getId(), dispatchUserId, nextTask, traceId);
        try {
            producer.sendIndexMessage(nextMessage);
        } catch (Exception ex) {
            markDispatchFailure(nextTask.getId(), nextTask.getRepoId(), ex.getMessage());
            log.warn("Dispatch next stage failed, repoId={}, taskId={}, taskType={}, traceId={}, reason={}",
                    nextTask.getRepoId(), nextTask.getId(), nextTask.getTaskType(), traceId, trimError(ex.getMessage()));
        }
    }

    private void onStageFailed(IndexTaskEntity task, Exception ex) {
        int nextRetry = safeInt(task.getRetryCount()) + 1;
        int maxRetry = resolveMaxRetry(task.getMaxRetry());
        String error = trimError(ex.getMessage());

        IndexTaskEntity update = new IndexTaskEntity();
        update.setId(task.getId());
        update.setRetryCount(nextRetry);
        update.setErrorMsg(error);
        if (nextRetry >= maxRetry) {
            update.setStatus(TaskStatus.FAILED);
            updateRepoIndexStatus(task.getRepoId(), RepoIndexStatus.FAILED);
            releaseRepoIndexLock(task.getRepoId(), resolveTraceId(task.getIdempotentKey(), task.getId()), "async-index-failed");
        } else {
            update.setStatus(TaskStatus.WAIT_RETRY);
        }
        indexTaskMapper.updateById(update);

        log.warn("Async index stage failed, taskId={}, repoId={}, taskType={}, retry={}/{}, error={}",
                task.getId(), task.getRepoId(), task.getTaskType(), nextRetry, maxRetry, error);
    }

    /**
     * 只有从 PENDING / WAIT_RETRY 成功切到 RUNNING 的消费者，才算真正拿到该阶段的执行权。
     */
    private boolean markTaskRunning(Long taskId) {
        return indexTaskMapper.update(null, Wrappers.<IndexTaskEntity>lambdaUpdate()
                .set(IndexTaskEntity::getStatus, TaskStatus.RUNNING)
                .set(IndexTaskEntity::getErrorMsg, null)
                .eq(IndexTaskEntity::getId, taskId)
                .in(IndexTaskEntity::getStatus, TaskStatus.PENDING, TaskStatus.WAIT_RETRY)) > 0;
    }

    private void markTaskSuccess(Long taskId) {
        IndexTaskEntity update = new IndexTaskEntity();
        update.setId(taskId);
        update.setStatus(TaskStatus.SUCCESS);
        update.setErrorMsg(null);
        indexTaskMapper.updateById(update);
    }

    private TaskStatus markDispatchFailure(Long taskId, Long repoId, String reason) {
        IndexTaskEntity task = indexTaskMapper.selectById(taskId);
        if (task == null) {
            return TaskStatus.FAILED;
        }

        int nextRetry = safeInt(task.getRetryCount()) + 1;
        int maxRetry = resolveMaxRetry(task.getMaxRetry());

        IndexTaskEntity update = new IndexTaskEntity();
        update.setId(taskId);
        update.setRetryCount(nextRetry);
        update.setErrorMsg("MQ send failed: " + trimError(reason));
        if (nextRetry >= maxRetry) {
            update.setStatus(TaskStatus.FAILED);
            updateRepoIndexStatus(repoId, RepoIndexStatus.FAILED);
            releaseRepoIndexLock(repoId, resolveTraceId(task.getIdempotentKey(), task.getId()), "mq-dispatch-failed");
        } else {
            update.setStatus(TaskStatus.WAIT_RETRY);
        }
        indexTaskMapper.updateById(update);
        return nextRetry >= maxRetry ? TaskStatus.FAILED : TaskStatus.WAIT_RETRY;
    }

    private void updateTaskToPending(Long taskId, String reason) {
        IndexTaskEntity update = new IndexTaskEntity();
        update.setId(taskId);
        update.setStatus(TaskStatus.PENDING);
        update.setErrorMsg(trimError(reason));
        indexTaskMapper.updateById(update);
    }

    private void markTaskFailed(IndexTaskEntity task, String reason) {
        IndexTaskEntity update = new IndexTaskEntity();
        update.setId(task.getId());
        update.setStatus(TaskStatus.FAILED);
        update.setErrorMsg(trimError(reason));
        indexTaskMapper.updateById(update);
        updateRepoIndexStatus(task.getRepoId(), RepoIndexStatus.FAILED);
        releaseRepoIndexLock(task.getRepoId(), resolveTraceId(task.getIdempotentKey(), task.getId()), "task-marked-failed");
    }

    private TaskType nextTaskType(TaskType currentType) {
        return switch (currentType) {
            case CLONE_REPO -> TaskType.PARSE_CODE;
            case PARSE_CODE -> TaskType.BUILD_CHUNK;
            case BUILD_CHUNK -> TaskType.VECTORIZE_CHUNK;
            default -> null;
        };
    }

    private void updateRepoIndexStatus(Long repoId, RepoIndexStatus status) {
        RepoEntity update = new RepoEntity();
        update.setId(repoId);
        update.setIndexStatus(status);
        repoMapper.updateById(update);
    }

    private RepoIndexMessage buildMessage(Long repoId,
                                          Long taskId,
                                          Long userId,
                                          IndexTaskEntity task,
                                          String traceId) {
        RepoIndexMessage message = new RepoIndexMessage();
        message.setRepoId(repoId);
        message.setTaskId(taskId);
        message.setUserId(userId);
        message.setTaskType(task.getTaskType());
        message.setIdempotentKey(task.getIdempotentKey());
        message.setTraceId(traceId);
        return message;
    }

    private IndexTaskEntity createOrGetTask(Long repoId, TaskType taskType, String idempotentKey) {
        IndexTaskEntity existing = indexTaskMapper.selectOne(Wrappers.<IndexTaskEntity>lambdaQuery()
                .eq(IndexTaskEntity::getIdempotentKey, idempotentKey)
                .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }

        IndexTaskEntity task = new IndexTaskEntity();
        task.setRepoId(repoId);
        task.setTaskType(taskType);
        task.setStatus(TaskStatus.PENDING);
        task.setRetryCount(0);
        task.setMaxRetry(resolveMaxRetry(null));
        task.setIdempotentKey(idempotentKey);
        try {
            indexTaskMapper.insert(task);
            return task;
        } catch (DuplicateKeyException ex) {
            IndexTaskEntity conflict = indexTaskMapper.selectOne(Wrappers.<IndexTaskEntity>lambdaQuery()
                    .eq(IndexTaskEntity::getIdempotentKey, idempotentKey)
                    .last("LIMIT 1"));
            if (conflict != null) {
                return conflict;
            }
            throw ex;
        }
    }

    /**
     * 幂等键把 repo / branch / taskType / traceId 绑在一起。
     * 同一次异步链路里，每个阶段都有稳定唯一标识，重复派发也能自然去重。
     */
    private String buildAsyncIdempotentKey(Long repoId, String branchName, TaskType taskType, String traceId) {
        return repoId + ":" + branchName + ":" + taskType.name() + ":ASYNC:" + traceId;
    }

    private Long findLatestActiveTaskId(Long repoId) {
        IndexTaskEntity latest = indexTaskMapper.selectOne(Wrappers.<IndexTaskEntity>lambdaQuery()
                .eq(IndexTaskEntity::getRepoId, repoId)
                .in(IndexTaskEntity::getStatus, TaskStatus.PENDING, TaskStatus.RUNNING, TaskStatus.WAIT_RETRY)
                .orderByDesc(IndexTaskEntity::getId)
                .last("LIMIT 1"));
        return latest == null ? null : latest.getId();
    }

    /**
     * 锁释放只发生在“链路终态”：
     * 1. VECTORIZE_CHUNK 成功，整个 repo 进入 INDEXED；
     * 2. 任何阶段最终 FAILED。
     *
     * Redis 锁不是事实源，所以这里即使释放失败，也只记录日志，不回滚 MySQL 任务状态。
     */
    private void releaseRepoIndexLock(Long repoId, String traceId, String reason) {
        if (!StringUtils.hasText(traceId)) {
            return;
        }
        boolean unlocked = repoIndexLockService.unlock(repoId, traceId);
        log.info("Repo index lock terminal release, repoId={}, traceId={}, reason={}, unlocked={}",
                repoId, traceId, reason, unlocked);
    }

    private String resolveTraceId(RepoIndexMessage message, IndexTaskEntity task) {
        if (message != null && StringUtils.hasText(message.getTraceId())) {
            return message.getTraceId().trim();
        }
        return resolveTraceId(task.getIdempotentKey(), task.getId());
    }

    private String resolveTraceId(String idempotentKey, Long taskId) {
        if (StringUtils.hasText(idempotentKey)) {
            String marker = ":ASYNC:";
            int index = idempotentKey.indexOf(marker);
            if (index >= 0 && index + marker.length() < idempotentKey.length()) {
                return idempotentKey.substring(index + marker.length());
            }
        }
        return "task-" + taskId;
    }

    private String normalizeBranch(String branchName) {
        return StringUtils.hasText(branchName) ? branchName.trim() : "main";
    }

    private int resolveMaxRetry(Integer taskMaxRetry) {
        int effective = taskMaxRetry == null ? configuredMaxRetry : taskMaxRetry;
        return effective <= 0 ? DEFAULT_MAX_RETRY : effective;
    }

    private Long resolveDispatchUserId(Long messageUserId, RepoEntity repo) {
        if (messageUserId != null) {
            return messageUserId;
        }
        if (repo != null && repo.getCreatedBy() != null) {
            return repo.getCreatedBy();
        }
        return 1L;
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private String trimError(String message) {
        if (!StringUtils.hasText(message)) {
            return "Unknown error";
        }
        String trimmed = message.trim();
        return trimmed.length() > 2000 ? trimmed.substring(0, 2000) : trimmed;
    }
}
