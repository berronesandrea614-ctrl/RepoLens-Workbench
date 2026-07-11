package com.repolens.service;

import com.repolens.domain.entity.IndexTaskEntity;
import com.repolens.domain.entity.RepoEntity;
import com.repolens.domain.enums.TaskStatus;
import com.repolens.domain.enums.TaskType;
import com.repolens.domain.mq.RepoIndexMessage;
import com.repolens.domain.vo.AsyncIndexResultVO;
import com.repolens.domain.vo.ImportRepoResultVO;
import com.repolens.domain.vo.SyncIndexResultVO;
import com.repolens.domain.vo.VectorizeResultVO;
import com.repolens.mapper.IndexTaskMapper;
import com.repolens.mapper.RepoMapper;
import com.repolens.producer.RepoIndexMessageProducer;
import com.repolens.security.PermissionService;
import com.repolens.service.impl.RepoAsyncIndexServiceImpl;
import com.repolens.service.impl.SyncIndexOrchestrator;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RepoAsyncIndexServiceImplTest {

    @Mock
    private RepoMapper repoMapper;
    @Mock
    private IndexTaskMapper indexTaskMapper;
    @Mock
    private PermissionService permissionService;
    @Mock
    private RepoIndexLockService repoIndexLockService;
    @Mock
    private RepoIndexMessageProducer producer;
    @Mock
    private GitRepositoryService gitRepositoryService;
    @Mock
    private JavaCodeParseService javaCodeParseService;
    @Mock
    private CodeChunkBuildService codeChunkBuildService;
    @Mock
    private ChunkVectorizeService chunkVectorizeService;
    @Mock
    private SyncIndexOrchestrator syncIndexOrchestrator;

    @InjectMocks
    private RepoAsyncIndexServiceImpl repoAsyncIndexService;

    /**
     * 单独运行本类时，MybatisPlus 的 lambda 缓存尚未由任何 Spring 上下文初始化，
     * Wrappers.lambdaUpdate(IndexTaskEntity::getStatus) 会抛 "can not find lambda cache"。
     * 这里手动注册 TableInfo，使测试类可独立运行（全量套件下重复注册无副作用）。
     */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), IndexTaskEntity.class);
    }

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(repoAsyncIndexService, "configuredMaxRetry", 3);
        ReflectionTestUtils.setField(repoAsyncIndexService, "consumerEnabled", true);
    }

    @Test
    void submitAsyncIndex_shouldRejectWhenLockAlreadyHeld() {
        RepoEntity repo = buildRepo();
        when(repoMapper.selectById(5L)).thenReturn(repo);
        when(permissionService.checkRepoPermission(1L, 5L)).thenReturn(true);
        when(repoIndexLockService.tryLock(eq(5L), any()))
                .thenReturn(new RepoIndexLockService.LockResult(false, false, false, "repo index is already running"));

        IndexTaskEntity activeTask = new IndexTaskEntity();
        activeTask.setId(88L);
        when(indexTaskMapper.selectOne(any())).thenReturn(activeTask);

        AsyncIndexResultVO result = repoAsyncIndexService.submitAsyncIndex(5L, 1L);

        Assertions.assertEquals(TaskStatus.RUNNING, result.getStatus());
        Assertions.assertEquals("repo index is already running", result.getMessage());
        Assertions.assertEquals(88L, result.getFirstTaskId());
        verify(producer, never()).sendIndexMessage(any());
        verify(indexTaskMapper, never()).insert(org.mockito.ArgumentMatchers.<IndexTaskEntity>any());
    }

    @Test
    void submitAsyncIndex_shouldContinueWhenLockDegraded() {
        RepoEntity repo = buildRepo();
        when(repoMapper.selectById(5L)).thenReturn(repo);
        when(permissionService.checkRepoPermission(1L, 5L)).thenReturn(true);
        when(repoIndexLockService.tryLock(eq(5L), any()))
                .thenReturn(new RepoIndexLockService.LockResult(true, false, true,
                        "index lock unavailable, task submitted without lock"));
        when(indexTaskMapper.selectOne(any())).thenReturn(null);
        when(indexTaskMapper.insert(any(IndexTaskEntity.class))).thenAnswer(invocation -> {
            IndexTaskEntity task = invocation.getArgument(0);
            task.setId(11L);
            return 1;
        });

        AsyncIndexResultVO result = repoAsyncIndexService.submitAsyncIndex(5L, 1L);

        Assertions.assertEquals(TaskStatus.PENDING, result.getStatus());
        Assertions.assertEquals("index lock unavailable, task submitted without lock", result.getMessage());
        Assertions.assertFalse(result.getLockAcquired());
        Assertions.assertTrue(result.getLockDegraded());
        verify(producer).sendIndexMessage(any());
    }

    @Test
    void submitAsyncIndex_consumerDisabled_runsInlineSyncPipelineInsteadOfEnqueue() {
        ReflectionTestUtils.setField(repoAsyncIndexService, "consumerEnabled", false);
        when(syncIndexOrchestrator.runSyncIndex(5L, 1L)).thenReturn(SyncIndexResultVO.builder()
                .repoId(5L)
                .status(TaskStatus.SUCCESS)
                .traceId("sync-t")
                .importResult(ImportRepoResultVO.builder().repoId(5L).taskId(7L).status(TaskStatus.SUCCESS).build())
                .build());

        AsyncIndexResultVO result = repoAsyncIndexService.submitAsyncIndex(5L, 1L);

        Assertions.assertEquals(TaskStatus.SUCCESS, result.getStatus());
        Assertions.assertEquals(7L, result.getFirstTaskId());
        Assertions.assertEquals("sync-t", result.getTraceId());
        verify(syncIndexOrchestrator).runSyncIndex(5L, 1L);
        // 消费者关闭时：不发 MQ、不落异步任务、submitAsyncIndex 自己也不置 INDEXING。
        verify(producer, never()).sendIndexMessage(any());
        verify(indexTaskMapper, never()).insert(org.mockito.ArgumentMatchers.<IndexTaskEntity>any());
        verify(repoMapper, never()).updateById(org.mockito.ArgumentMatchers.<RepoEntity>any());
    }

    @Test
    void submitAsyncIndex_consumerDisabled_mapsStageFailureWithoutEnqueue() {
        ReflectionTestUtils.setField(repoAsyncIndexService, "consumerEnabled", false);
        when(syncIndexOrchestrator.runSyncIndex(5L, 1L)).thenReturn(SyncIndexResultVO.builder()
                .repoId(5L)
                .status(TaskStatus.FAILED)
                .failedStage(TaskType.PARSE_CODE.name())
                .errorMsg("parse boom")
                .traceId("sync-t")
                .build());

        AsyncIndexResultVO result = repoAsyncIndexService.submitAsyncIndex(5L, 1L);

        Assertions.assertEquals(TaskStatus.FAILED, result.getStatus());
        Assertions.assertEquals("sync index failed at PARSE_CODE: parse boom", result.getMessage());
        verify(producer, never()).sendIndexMessage(any());
    }

    // -----------------------------------------------------------------------
    // Finding #2: afterCommit MQ-send deferral tests
    // -----------------------------------------------------------------------

    /**
     * With an active {@link TransactionSynchronizationManager} synchronization (simulating the
     * Spring-managed transaction context that surrounds a real {@code @Transactional} method call),
     * {@code submitAsyncIndex} must NOT send the MQ message immediately.  Instead it registers a
     * {@link TransactionSynchronization} and defers the send to {@link TransactionSynchronization#afterCommit()}.
     *
     * <p>Also verifies the fallback: when no synchronization is active (normal unit-test context),
     * the message is sent immediately inside {@code submitAsyncIndex}.
     */
    @Test
    void submitAsyncIndex_withActiveSynchronization_defersMqSendToAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            RepoEntity repo = buildRepo();
            when(repoMapper.selectById(5L)).thenReturn(repo);
            when(permissionService.checkRepoPermission(1L, 5L)).thenReturn(true);
            when(repoIndexLockService.tryLock(eq(5L), any()))
                    .thenReturn(new RepoIndexLockService.LockResult(true, true, false, "lock acquired"));
            // No pre-existing task → createOrGetTask creates a new one.
            when(indexTaskMapper.selectOne(any())).thenReturn(null);
            when(indexTaskMapper.insert(any(IndexTaskEntity.class))).thenAnswer(inv -> {
                ((IndexTaskEntity) inv.getArgument(0)).setId(99L);
                return 1;
            });

            AsyncIndexResultVO result = repoAsyncIndexService.submitAsyncIndex(5L, 1L);

            Assertions.assertNotNull(result, "result must not be null");
            // --- Point 1: MQ send must NOT have happened yet ---
            verify(producer, never()).sendIndexMessage(any());

            // Collect and fire afterCommit on every registered synchronization.
            List<TransactionSynchronization> syncs =
                    TransactionSynchronizationManager.getSynchronizations();
            Assertions.assertFalse(syncs.isEmpty(),
                    "A TransactionSynchronization must have been registered for the deferred MQ send");
            for (TransactionSynchronization sync : syncs) {
                sync.afterCommit();
            }

            // --- Point 2: MQ send must happen exactly once after afterCommit() ---
            verify(producer, times(1)).sendIndexMessage(any());
        } finally {
            // Always clean up thread-local synchronization state — prevents leakage between tests.
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /**
     * Without an active synchronization (the normal unit-test / non-transactional path),
     * {@code submitAsyncIndex} sends the MQ message immediately inside the method body.
     * This exercises the {@code else} branch of the {@code isSynchronizationActive()} guard.
     */
    @Test
    void submitAsyncIndex_noActiveSynchronization_sendsMqImmediately() {
        // No initSynchronization() call → isSynchronizationActive() == false.
        RepoEntity repo = buildRepo();
        when(repoMapper.selectById(5L)).thenReturn(repo);
        when(permissionService.checkRepoPermission(1L, 5L)).thenReturn(true);
        when(repoIndexLockService.tryLock(eq(5L), any()))
                .thenReturn(new RepoIndexLockService.LockResult(true, true, false, "lock acquired"));
        when(indexTaskMapper.selectOne(any())).thenReturn(null);
        when(indexTaskMapper.insert(any(IndexTaskEntity.class))).thenAnswer(inv -> {
            ((IndexTaskEntity) inv.getArgument(0)).setId(77L);
            return 1;
        });

        AsyncIndexResultVO result = repoAsyncIndexService.submitAsyncIndex(5L, 1L);

        Assertions.assertNotNull(result);
        // MQ must have been sent directly — no afterCommit deferral.
        verify(producer, times(1)).sendIndexMessage(any());
    }

    @Test
    void handleIndexMessage_shouldReleaseLockWhenFinalStageSucceeds() {
        IndexTaskEntity task = buildVectorizeTask(0, 3);
        RepoIndexMessage message = buildMessage();
        when(indexTaskMapper.selectById(10L)).thenReturn(task);
        when(indexTaskMapper.update(eq(null), any())).thenReturn(1);
        when(chunkVectorizeService.vectorizeRepoChunks(5L, 1L)).thenReturn(VectorizeResultVO.builder()
                .repoId(5L)
                .pendingChunkCount(23)
                .embeddedChunkCount(23)
                .failedChunkCount(0)
                .status(TaskStatus.SUCCESS)
                .errorMsg(null)
                .build());
        when(repoIndexLockService.unlock(5L, "trace-1")).thenReturn(true);

        repoAsyncIndexService.handleIndexMessage(message);

        verify(repoIndexLockService).unlock(5L, "trace-1");
        verify(producer, never()).sendIndexMessage(any());
    }

    @Test
    void handleIndexMessage_shouldReleaseLockWhenFinalStageFails() {
        IndexTaskEntity task = buildVectorizeTask(2, 3);
        RepoIndexMessage message = buildMessage();
        when(indexTaskMapper.selectById(10L)).thenReturn(task);
        when(indexTaskMapper.update(eq(null), any())).thenReturn(1);
        when(chunkVectorizeService.vectorizeRepoChunks(5L, 1L)).thenReturn(VectorizeResultVO.builder()
                .repoId(5L)
                .pendingChunkCount(23)
                .embeddedChunkCount(10)
                .failedChunkCount(13)
                .status(TaskStatus.FAILED)
                .errorMsg("batch failed")
                .build());
        when(repoIndexLockService.unlock(5L, "trace-1")).thenReturn(true);

        repoAsyncIndexService.handleIndexMessage(message);

        verify(repoIndexLockService).unlock(5L, "trace-1");
    }

    private RepoEntity buildRepo() {
        RepoEntity repo = new RepoEntity();
        repo.setId(5L);
        repo.setBranchName("main");
        repo.setCreatedBy(1L);
        return repo;
    }

    private IndexTaskEntity buildVectorizeTask(int retryCount, int maxRetry) {
        IndexTaskEntity task = new IndexTaskEntity();
        task.setId(10L);
        task.setRepoId(5L);
        task.setTaskType(TaskType.VECTORIZE_CHUNK);
        task.setStatus(TaskStatus.PENDING);
        task.setRetryCount(retryCount);
        task.setMaxRetry(maxRetry);
        task.setIdempotentKey("5:main:VECTORIZE_CHUNK:ASYNC:trace-1");
        return task;
    }

    private RepoIndexMessage buildMessage() {
        RepoIndexMessage message = new RepoIndexMessage();
        message.setRepoId(5L);
        message.setTaskId(10L);
        message.setTaskType(TaskType.VECTORIZE_CHUNK);
        message.setUserId(1L);
        message.setTraceId("trace-1");
        message.setIdempotentKey("5:main:VECTORIZE_CHUNK:ASYNC:trace-1");
        return message;
    }
}
