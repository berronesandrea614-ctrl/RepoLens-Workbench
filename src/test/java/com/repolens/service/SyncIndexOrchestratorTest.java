package com.repolens.service;

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
import com.repolens.service.ComprehensionDebtService;
import com.repolens.service.impl.SyncIndexOrchestrator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 同步索引编排器单测：
 * 1. 成功链路四阶段按序执行，repo 状态 INDEXING → INDEXED，锁必释放；
 * 2. 阶段返回 FAILED / 阶段抛异常时，repo 状态必为 FAILED（绝不残留 INDEXING）；
 * 3. 锁被占用时直接返回 RUNNING，不执行任何阶段、不改状态。
 */
@ExtendWith(MockitoExtension.class)
class SyncIndexOrchestratorTest {

    @Mock
    private RepoMapper repoMapper;
    @Mock
    private PermissionService permissionService;
    @Mock
    private RepoIndexLockService repoIndexLockService;
    @Mock
    private IndexTaskService indexTaskService;
    @Mock
    private GitRepositoryService gitRepositoryService;
    @Mock
    private JavaCodeParseService javaCodeParseService;
    @Mock
    private CodeChunkBuildService codeChunkBuildService;
    @Mock
    private ChunkVectorizeService chunkVectorizeService;
    @Mock
    private ComprehensionDebtService comprehensionDebtService;

    @InjectMocks
    private SyncIndexOrchestrator orchestrator;

    @Test
    void runSyncIndex_executesStagesInOrderAndEndsIndexed() {
        stubRepoAndLock(true);
        stubCloneTask();
        when(gitRepositoryService.importRepository(5L, 7L, 1L)).thenReturn(importOk());
        when(javaCodeParseService.parseRepository(5L, 1L)).thenReturn(parseOk());
        when(codeChunkBuildService.buildChunks(5L, 1L)).thenReturn(chunksOk());
        when(chunkVectorizeService.vectorizeRepoChunks(5L, 1L)).thenReturn(vectorizeOk());

        SyncIndexResultVO result = orchestrator.runSyncIndex(5L, 1L);

        Assertions.assertEquals(TaskStatus.SUCCESS, result.getStatus());
        Assertions.assertNull(result.getFailedStage());
        Assertions.assertEquals(23, result.getVectorizeResult().getEmbeddedChunkCount());

        InOrder inOrder = Mockito.inOrder(
                gitRepositoryService, javaCodeParseService, codeChunkBuildService, chunkVectorizeService);
        inOrder.verify(gitRepositoryService).importRepository(5L, 7L, 1L);
        inOrder.verify(javaCodeParseService).parseRepository(5L, 1L);
        inOrder.verify(codeChunkBuildService).buildChunks(5L, 1L);
        inOrder.verify(chunkVectorizeService).vectorizeRepoChunks(5L, 1L);

        Assertions.assertEquals(List.of(RepoIndexStatus.INDEXING, RepoIndexStatus.INDEXED), capturedStatusUpdates());
        verify(repoIndexLockService).unlock(eq(5L), anyString());
    }

    @Test
    void runSyncIndex_stageReportsFailed_setsRepoFailedAndStops() {
        stubRepoAndLock(true);
        stubCloneTask();
        when(gitRepositoryService.importRepository(5L, 7L, 1L)).thenReturn(importOk());
        when(javaCodeParseService.parseRepository(5L, 1L)).thenReturn(ParseRepoResultVO.builder()
                .repoId(5L).status(TaskStatus.FAILED).errorMsg("parse boom").build());

        SyncIndexResultVO result = orchestrator.runSyncIndex(5L, 1L);

        Assertions.assertEquals(TaskStatus.FAILED, result.getStatus());
        Assertions.assertEquals(TaskType.PARSE_CODE.name(), result.getFailedStage());
        Assertions.assertEquals("parse boom", result.getErrorMsg());
        verify(codeChunkBuildService, never()).buildChunks(anyLong(), anyLong());
        verify(chunkVectorizeService, never()).vectorizeRepoChunks(anyLong(), anyLong());

        Assertions.assertEquals(List.of(RepoIndexStatus.INDEXING, RepoIndexStatus.FAILED), capturedStatusUpdates());
        verify(repoIndexLockService).unlock(eq(5L), anyString());
    }

    @Test
    void runSyncIndex_stageThrows_setsRepoFailedNotStuckIndexing() {
        stubRepoAndLock(true);
        stubCloneTask();
        when(gitRepositoryService.importRepository(5L, 7L, 1L)).thenReturn(importOk());
        when(javaCodeParseService.parseRepository(5L, 1L)).thenReturn(parseOk());
        when(codeChunkBuildService.buildChunks(5L, 1L)).thenThrow(new RuntimeException("chunk crash"));

        SyncIndexResultVO result = orchestrator.runSyncIndex(5L, 1L);

        Assertions.assertEquals(TaskStatus.FAILED, result.getStatus());
        Assertions.assertEquals(TaskType.BUILD_CHUNK.name(), result.getFailedStage());
        Assertions.assertEquals("chunk crash", result.getErrorMsg());
        verify(chunkVectorizeService, never()).vectorizeRepoChunks(anyLong(), anyLong());

        // 关键断言：最终状态是 FAILED，绝不能停在 INDEXING。
        List<RepoIndexStatus> statuses = capturedStatusUpdates();
        Assertions.assertEquals(RepoIndexStatus.FAILED, statuses.get(statuses.size() - 1));
        verify(repoIndexLockService).unlock(eq(5L), anyString());
    }

    @Test
    void runSyncIndex_lockBusy_returnsRunningWithoutTouchingAnything() {
        RepoEntity repo = buildRepo();
        when(repoMapper.selectById(5L)).thenReturn(repo);
        when(permissionService.checkRepoPermission(1L, 5L)).thenReturn(true);
        when(repoIndexLockService.tryLock(eq(5L), anyString()))
                .thenReturn(new RepoIndexLockService.LockResult(false, false, false, "repo index is already running"));

        SyncIndexResultVO result = orchestrator.runSyncIndex(5L, 1L);

        Assertions.assertEquals(TaskStatus.RUNNING, result.getStatus());
        Assertions.assertEquals("repo index is already running", result.getErrorMsg());
        verify(repoMapper, never()).updateById(any(RepoEntity.class));
        verify(gitRepositoryService, never()).importRepository(anyLong(), anyLong(), anyLong());
        verify(repoIndexLockService, never()).unlock(anyLong(), anyString());
    }

    private void stubRepoAndLock(boolean lockAcquired) {
        RepoEntity repo = buildRepo();
        when(repoMapper.selectById(5L)).thenReturn(repo);
        when(permissionService.checkRepoPermission(1L, 5L)).thenReturn(true);
        when(repoIndexLockService.tryLock(eq(5L), anyString()))
                .thenReturn(new RepoIndexLockService.LockResult(true, lockAcquired, false, "locked"));
    }

    private void stubCloneTask() {
        IndexTaskEntity cloneTask = new IndexTaskEntity();
        cloneTask.setId(7L);
        cloneTask.setRepoId(5L);
        cloneTask.setTaskType(TaskType.CLONE_REPO);
        when(indexTaskService.findLatestCloneTaskForImport(5L)).thenReturn(cloneTask);
    }

    private List<RepoIndexStatus> capturedStatusUpdates() {
        ArgumentCaptor<RepoEntity> captor = ArgumentCaptor.forClass(RepoEntity.class);
        verify(repoMapper, Mockito.atLeastOnce()).updateById(captor.capture());
        return captor.getAllValues().stream().map(RepoEntity::getIndexStatus).toList();
    }

    private RepoEntity buildRepo() {
        RepoEntity repo = new RepoEntity();
        repo.setId(5L);
        repo.setBranchName("main");
        repo.setCreatedBy(1L);
        return repo;
    }

    private ImportRepoResultVO importOk() {
        return ImportRepoResultVO.builder().repoId(5L).taskId(7L).status(TaskStatus.SUCCESS).build();
    }

    private ParseRepoResultVO parseOk() {
        return ParseRepoResultVO.builder().repoId(5L).status(TaskStatus.SUCCESS).build();
    }

    private BuildChunkResultVO chunksOk() {
        return BuildChunkResultVO.builder().repoId(5L).totalChunkCount(23).status(TaskStatus.SUCCESS).build();
    }

    private VectorizeResultVO vectorizeOk() {
        return VectorizeResultVO.builder()
                .repoId(5L).pendingChunkCount(23).embeddedChunkCount(23).failedChunkCount(0)
                .status(TaskStatus.SUCCESS).build();
    }
}
