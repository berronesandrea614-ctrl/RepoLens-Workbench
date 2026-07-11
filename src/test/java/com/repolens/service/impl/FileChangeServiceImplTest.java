package com.repolens.service.impl;

import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.dto.FileWriteRequest;
import com.repolens.domain.entity.ChangeRiskFlagEntity;
import com.repolens.domain.entity.FileChangeLogEntity;
import com.repolens.domain.entity.RepoEntity;
import com.repolens.domain.enums.RepoIndexStatus;
import com.repolens.domain.vo.FileChangeDetailVO;
import com.repolens.domain.vo.FileChangeVO;
import com.repolens.mapper.FileChangeLogMapper;
import com.repolens.mapper.RepoMapper;
import com.repolens.security.PermissionService;
import com.repolens.service.ChangeRiskService;
import com.repolens.service.ComprehensionDebtService;
import com.repolens.service.ProvenanceService;
import com.repolens.service.RepoFileWriteService;
import com.repolens.service.TraceabilityService;
import com.repolens.service.support.ShadowWorkspaceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileChangeServiceImplTest {

    private PermissionService permissionService;
    private FileChangeLogMapper fileChangeLogMapper;
    private RepoFileWriteService repoFileWriteService;
    private RepoMapper repoMapper;
    private ComprehensionDebtService comprehensionDebtService;
    private ProvenanceService provenanceService;
    private TraceabilityService traceabilityService;
    private ChangeRiskService changeRiskService;
    private FileChangeServiceImpl service;

    @BeforeEach
    void setup() {
        permissionService = mock(PermissionService.class);
        fileChangeLogMapper = mock(FileChangeLogMapper.class);
        repoFileWriteService = mock(RepoFileWriteService.class);
        repoMapper = mock(RepoMapper.class);
        comprehensionDebtService = mock(ComprehensionDebtService.class);
        provenanceService = mock(ProvenanceService.class);
        traceabilityService = mock(TraceabilityService.class);
        changeRiskService = mock(ChangeRiskService.class);
        ShadowWorkspaceManager shadowManager = mock(ShadowWorkspaceManager.class);
        when(permissionService.checkRepoPermission(anyLong(), anyLong())).thenReturn(true);
        when(traceabilityService.markDechainSafe(anyLong(), any(), any(Boolean.class))).thenReturn(List.of());
        // Default: no blockers (gate passes); no flags in DB → detectSync called as fallback
        when(changeRiskService.getUnacknowledgedBlockers(anyLong())).thenReturn(List.of());
        when(changeRiskService.detectSync(anyLong(), anyLong())).thenReturn(List.of());
        when(changeRiskService.hasFlags(anyLong())).thenReturn(false);
        service = new FileChangeServiceImpl(permissionService, fileChangeLogMapper, repoFileWriteService, repoMapper, comprehensionDebtService, provenanceService, traceabilityService, changeRiskService, shadowManager);
    }

    private FileChangeLogEntity change(long id, long repoId, String path, String oldC, String newC) {
        return change(id, repoId, path, oldC, newC, FileChangeLogEntity.STATUS_APPLIED);
    }

    private FileChangeLogEntity change(long id, long repoId, String path, String oldC, String newC, String status) {
        FileChangeLogEntity e = new FileChangeLogEntity();
        e.setId(id);
        e.setRepoId(repoId);
        e.setSessionId(9L);
        e.setFilePath(path);
        e.setOldContent(oldC);
        e.setNewContent(newC);
        e.setReverted(0);
        e.setStatus(status);
        return e;
    }

    @Test
    void apply_writesNewContentToDiskAndMarksApplied() {
        FileChangeLogEntity target = change(60L, 5L, "src/A.java", "OLD", "NEW", FileChangeLogEntity.STATUS_PROPOSED);
        when(fileChangeLogMapper.selectById(eq(60L))).thenReturn(target);

        FileChangeVO vo = service.apply(1L, 5L, 60L);

        // newContent 被写盘。
        ArgumentCaptor<FileWriteRequest> writeCaptor = ArgumentCaptor.forClass(FileWriteRequest.class);
        verify(repoFileWriteService).writeFile(eq(1L), writeCaptor.capture());
        assertThat(writeCaptor.getValue().getRepoId()).isEqualTo(5L);
        assertThat(writeCaptor.getValue().getFilePath()).isEqualTo("src/A.java");
        assertThat(writeCaptor.getValue().getContent()).isEqualTo("NEW");

        // 状态置为 APPLIED 并落库。
        verify(fileChangeLogMapper).updateById(target);
        assertThat(target.getStatus()).isEqualTo(FileChangeLogEntity.STATUS_APPLIED);
        assertThat(vo.getChangeId()).isEqualTo(60L);
    }

    @Test
    void apply_rejectsNonProposedChange() {
        when(fileChangeLogMapper.selectById(eq(60L)))
                .thenReturn(change(60L, 5L, "src/A.java", "OLD", "NEW", FileChangeLogEntity.STATUS_APPLIED));
        assertThatThrownBy(() -> service.apply(1L, 5L, 60L)).isInstanceOf(BizException.class);
        verify(repoFileWriteService, never()).writeFile(any(), any());
    }

    @Test
    void reject_marksRejectedWithoutWriting() {
        FileChangeLogEntity target = change(61L, 5L, "src/A.java", "OLD", "NEW", FileChangeLogEntity.STATUS_PROPOSED);
        when(fileChangeLogMapper.selectById(eq(61L))).thenReturn(target);

        FileChangeVO vo = service.reject(1L, 5L, 61L);

        verify(repoFileWriteService, never()).writeFile(any(), any());
        verify(fileChangeLogMapper).updateById(target);
        assertThat(target.getStatus()).isEqualTo(FileChangeLogEntity.STATUS_REJECTED);
        assertThat(vo.getChangeId()).isEqualTo(61L);
    }

    @Test
    void applyAll_appliesEveryProposedChange() {
        FileChangeLogEntity a = change(70L, 5L, "src/A.java", "OA", "NA", FileChangeLogEntity.STATUS_PROPOSED);
        FileChangeLogEntity b = change(71L, 5L, "src/B.java", "OB", "NB", FileChangeLogEntity.STATUS_PROPOSED);
        when(fileChangeLogMapper.selectList(any())).thenReturn(List.of(a, b));

        List<FileChangeVO> vos = service.applyAll(1L, 5L, 9L);

        verify(repoFileWriteService, times(2)).writeFile(eq(1L), any(FileWriteRequest.class));
        assertThat(a.getStatus()).isEqualTo(FileChangeLogEntity.STATUS_APPLIED);
        assertThat(b.getStatus()).isEqualTo(FileChangeLogEntity.STATUS_APPLIED);
        assertThat(vos).hasSize(2);
    }

    @Test
    void rejectAll_rejectsEveryProposedChangeWithoutWriting() {
        FileChangeLogEntity a = change(70L, 5L, "src/A.java", "OA", "NA", FileChangeLogEntity.STATUS_PROPOSED);
        FileChangeLogEntity b = change(71L, 5L, "src/B.java", "OB", "NB", FileChangeLogEntity.STATUS_PROPOSED);
        when(fileChangeLogMapper.selectList(any())).thenReturn(List.of(a, b));

        List<FileChangeVO> vos = service.rejectAll(1L, 5L, 9L);

        verify(repoFileWriteService, never()).writeFile(any(), any());
        assertThat(a.getStatus()).isEqualTo(FileChangeLogEntity.STATUS_REJECTED);
        assertThat(b.getStatus()).isEqualTo(FileChangeLogEntity.STATUS_REJECTED);
        assertThat(vos).hasSize(2);
    }

    @Test
    void revert_restoresOldContentAndAppendsRevertLog() {
        FileChangeLogEntity target = change(50L, 5L, "src/A.java", "ORIGINAL", "MODIFIED");
        when(fileChangeLogMapper.selectById(eq(50L))).thenReturn(target);
        when(fileChangeLogMapper.insert(any(FileChangeLogEntity.class))).thenAnswer(inv -> {
            ((FileChangeLogEntity) inv.getArgument(0)).setId(51L);
            return 1;
        });

        FileChangeVO vo = service.revert(1L, 5L, 50L);

        // 旧内容被写回文件。
        ArgumentCaptor<FileWriteRequest> writeCaptor = ArgumentCaptor.forClass(FileWriteRequest.class);
        verify(repoFileWriteService).writeFile(eq(1L), writeCaptor.capture());
        assertThat(writeCaptor.getValue().getRepoId()).isEqualTo(5L);
        assertThat(writeCaptor.getValue().getFilePath()).isEqualTo("src/A.java");
        assertThat(writeCaptor.getValue().getContent()).isEqualTo("ORIGINAL");

        // 原变更标记 reverted=1。
        verify(fileChangeLogMapper).updateById(target);
        assertThat(target.getReverted()).isEqualTo(1);

        // 追加回滚记录：old=被撤销内容(MODIFIED)，new=恢复内容(ORIGINAL)。
        ArgumentCaptor<FileChangeLogEntity> insertCaptor = ArgumentCaptor.forClass(FileChangeLogEntity.class);
        verify(fileChangeLogMapper).insert(insertCaptor.capture());
        FileChangeLogEntity revertLog = insertCaptor.getValue();
        assertThat(revertLog.getRepoId()).isEqualTo(5L);
        assertThat(revertLog.getSessionId()).isEqualTo(9L);
        assertThat(revertLog.getOldContent()).isEqualTo("MODIFIED");
        assertThat(revertLog.getNewContent()).isEqualTo("ORIGINAL");
        assertThat(revertLog.getReverted()).isEqualTo(0);

        assertThat(vo.getChangeId()).isEqualTo(51L);
        assertThat(vo.getFilePath()).isEqualTo("src/A.java");
    }

    @Test
    void revert_rejectsChangeFromOtherRepo() {
        when(fileChangeLogMapper.selectById(eq(50L))).thenReturn(change(50L, 999L, "src/A.java", "a", "b"));
        assertThatThrownBy(() -> service.revert(1L, 5L, 50L)).isInstanceOf(BizException.class);
    }

    @Test
    void revert_rejectsMissingChange() {
        when(fileChangeLogMapper.selectById(eq(50L))).thenReturn(null);
        assertThatThrownBy(() -> service.revert(1L, 5L, 50L)).isInstanceOf(BizException.class);
    }

    @Test
    void revert_rejectsWhenNoPermission() {
        when(permissionService.checkRepoPermission(anyLong(), anyLong())).thenReturn(false);
        assertThatThrownBy(() -> service.revert(1L, 5L, 50L)).isInstanceOf(BizException.class);
    }

    // ——————————— E P1: BLOCK 门 ———————————

    private ChangeRiskFlagEntity blockFlag(long changeId) {
        ChangeRiskFlagEntity f = new ChangeRiskFlagEntity();
        f.setChangeId(changeId);
        f.setSeverity("BLOCK");
        f.setCategory("DESTRUCTIVE");
        f.setRuleCode("DELETE_FILE");
        f.setAcknowledged(0);
        return f;
    }

    @Test
    void apply_withUnacknowledgedBlockAndAckFalse_throwsBadRequest() {
        FileChangeLogEntity target = change(60L, 5L, "src/A.java", "OLD", "NEW", FileChangeLogEntity.STATUS_PROPOSED);
        when(fileChangeLogMapper.selectById(eq(60L))).thenReturn(target);
        when(changeRiskService.getUnacknowledgedBlockers(eq(60L))).thenReturn(List.of(blockFlag(60L)));

        assertThatThrownBy(() -> service.apply(1L, 5L, 60L, false))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.BAD_REQUEST.getCode()));

        verify(repoFileWriteService, never()).writeFile(any(), any());
        verify(repoFileWriteService, never()).createFile(any(), any());
    }

    @Test
    void apply_withAckTrue_skipsGateAndAppliesNormally() {
        FileChangeLogEntity target = change(60L, 5L, "src/A.java", "OLD", "NEW", FileChangeLogEntity.STATUS_PROPOSED);
        when(fileChangeLogMapper.selectById(eq(60L))).thenReturn(target);
        // Even if blockers exist, ack=true bypasses the gate
        when(changeRiskService.getUnacknowledgedBlockers(eq(60L))).thenReturn(List.of(blockFlag(60L)));

        FileChangeVO vo = service.apply(1L, 5L, 60L, true);

        verify(repoFileWriteService).writeFile(eq(1L), any(FileWriteRequest.class));
        assertThat(target.getStatus()).isEqualTo(FileChangeLogEntity.STATUS_APPLIED);
        assertThat(vo.getChangeId()).isEqualTo(60L);
        // Gate must not be consulted when ack=true
        verify(changeRiskService, never()).getUnacknowledgedBlockers(anyLong());
        verify(changeRiskService, never()).detectSync(anyLong(), anyLong());
    }

    @Test
    void apply_noDbFlagButDetectSyncReturnsBlock_raceCondition_stillBlocks() {
        FileChangeLogEntity target = change(60L, 5L, "src/A.java", "OLD", "NEW", FileChangeLogEntity.STATUS_PROPOSED);
        when(fileChangeLogMapper.selectById(eq(60L))).thenReturn(target);
        // DB has no persisted flag yet (async hasn't landed) → hasFlags=false → detectSync triggered
        when(changeRiskService.getUnacknowledgedBlockers(eq(60L))).thenReturn(List.of());
        when(changeRiskService.hasFlags(eq(60L))).thenReturn(false);
        // Sync detector finds a BLOCK → race-condition fallback
        when(changeRiskService.detectSync(eq(5L), eq(60L))).thenReturn(List.of(blockFlag(60L)));

        assertThatThrownBy(() -> service.apply(1L, 5L, 60L, false))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.BAD_REQUEST.getCode()));

        verify(repoFileWriteService, never()).writeFile(any(), any());
    }

    @Test
    void apply_flagsExistAndAllAcknowledged_withAckFalse_appliesNormally() {
        // Flags are in DB but all acknowledged → getUnacknowledgedBlockers returns empty → hasFlags=true
        // → detectSync must NOT be called → gate passes
        FileChangeLogEntity target = change(60L, 5L, "src/A.java", "OLD", "NEW", FileChangeLogEntity.STATUS_PROPOSED);
        when(fileChangeLogMapper.selectById(eq(60L))).thenReturn(target);
        when(changeRiskService.getUnacknowledgedBlockers(eq(60L))).thenReturn(List.of()); // all acked
        when(changeRiskService.hasFlags(eq(60L))).thenReturn(true); // flags exist in DB

        FileChangeVO vo = service.apply(1L, 5L, 60L, false);

        // detectSync must NOT be consulted — we trust the DB ack state
        verify(changeRiskService, never()).detectSync(anyLong(), anyLong());
        verify(repoFileWriteService).writeFile(eq(1L), any(FileWriteRequest.class));
        assertThat(target.getStatus()).isEqualTo(FileChangeLogEntity.STATUS_APPLIED);
        assertThat(vo.getChangeId()).isEqualTo(60L);
    }

    @Test
    void apply_noBlockersAtAll_appliesNormally() {
        FileChangeLogEntity target = change(60L, 5L, "src/A.java", "OLD", "NEW", FileChangeLogEntity.STATUS_PROPOSED);
        when(fileChangeLogMapper.selectById(eq(60L))).thenReturn(target);
        // Both DB and sync check return empty
        when(changeRiskService.getUnacknowledgedBlockers(eq(60L))).thenReturn(List.of());
        when(changeRiskService.detectSync(eq(5L), eq(60L))).thenReturn(List.of());

        FileChangeVO vo = service.apply(1L, 5L, 60L, false);

        verify(repoFileWriteService).writeFile(eq(1L), any(FileWriteRequest.class));
        assertThat(target.getStatus()).isEqualTo(FileChangeLogEntity.STATUS_APPLIED);
        assertThat(vo.getChangeId()).isEqualTo(60L);
    }

    @Test
    void listChanges_mapsToDetailVo() {
        when(fileChangeLogMapper.selectList(any())).thenReturn(List.of(
                change(50L, 5L, "src/A.java", "ORIGINAL", "MODIFIED")));

        List<FileChangeDetailVO> details = service.listChanges(1L, 5L, 9L);

        assertThat(details).hasSize(1);
        assertThat(details.get(0).getFilePath()).isEqualTo("src/A.java");
        assertThat(details.get(0).getOldContent()).isEqualTo("ORIGINAL");
        assertThat(details.get(0).getNewContent()).isEqualTo("MODIFIED");
    }

    // ——————————— F3: apply marks index STALE ———————————

    @Test
    void apply_marksIndexStaleAfterWritingDisk() {
        FileChangeLogEntity target = change(60L, 5L, "src/A.java", "OLD", "NEW", FileChangeLogEntity.STATUS_PROPOSED);
        when(fileChangeLogMapper.selectById(eq(60L))).thenReturn(target);

        service.apply(1L, 5L, 60L);

        // 写盘后应把 repo 索引置 STALE。
        ArgumentCaptor<RepoEntity> repoCaptor = ArgumentCaptor.forClass(RepoEntity.class);
        verify(repoMapper).updateById(repoCaptor.capture());
        assertThat(repoCaptor.getValue().getIndexStatus()).isEqualTo(RepoIndexStatus.STALE);
        assertThat(repoCaptor.getValue().getId()).isEqualTo(5L);
    }

    @Test
    void apply_createFileContent_routesToCreateFileNotWriteFile() {
        // createFileContent 产生的 PROPOSED：oldContent 为空字符串
        FileChangeLogEntity target = change(62L, 5L, "src/New.java", "", "class New {}", FileChangeLogEntity.STATUS_PROPOSED);
        when(fileChangeLogMapper.selectById(eq(62L))).thenReturn(target);

        service.apply(1L, 5L, 62L);

        // 新建文件路径：调用 createFile，不调用 writeFile
        verify(repoFileWriteService).createFile(eq(1L), any(FileWriteRequest.class));
        verify(repoFileWriteService, never()).writeFile(any(), any());
    }

    @Test
    void apply_editOrWriteFileContent_routesToWriteFileNotCreateFile() {
        // writeFileContent / editFileContent 产生的 PROPOSED：oldContent 非空
        FileChangeLogEntity target = change(63L, 5L, "src/A.java", "OLD", "NEW", FileChangeLogEntity.STATUS_PROPOSED);
        when(fileChangeLogMapper.selectById(eq(63L))).thenReturn(target);

        service.apply(1L, 5L, 63L);

        // 覆盖写路径：调用 writeFile，不调用 createFile
        verify(repoFileWriteService).writeFile(eq(1L), any(FileWriteRequest.class));
        verify(repoFileWriteService, never()).createFile(any(), any());
    }

    @Test
    void apply_emptyOldContent_withOpTypeWrite_routesToWriteFileNotCreateFile() {
        // 覆盖一个内容为空的已存在文件：oldContent=""、opType=WRITE → 应走 writeFile 路径，不走 createFile。
        // 若路由仅靠 oldContent 空启发，则会误走 createFile（CREATE_NEW）→ 已存在文件抛错。
        FileChangeLogEntity target = change(64L, 5L, "src/Empty.java", "", "// filled", FileChangeLogEntity.STATUS_PROPOSED);
        target.setOpType(FileChangeLogEntity.OP_TYPE_WRITE);
        when(fileChangeLogMapper.selectById(eq(64L))).thenReturn(target);

        service.apply(1L, 5L, 64L);

        // opType=WRITE 优先于 oldContent 空启发 → 走 writeFile
        verify(repoFileWriteService).writeFile(eq(1L), any(FileWriteRequest.class));
        verify(repoFileWriteService, never()).createFile(any(), any());
    }

    @Test
    void apply_deleteFile_callsDeleteFileNotWriteFile() {
        FileChangeLogEntity target = change(65L, 5L, "src/A.java", "OLD", null, FileChangeLogEntity.STATUS_PROPOSED);
        target.setOpType(FileChangeLogEntity.OP_TYPE_DELETE);
        when(fileChangeLogMapper.selectById(eq(65L))).thenReturn(target);

        service.apply(1L, 5L, 65L);

        // DELETE branch: should call deleteFile, not writeFile or createFile
        verify(repoFileWriteService).deleteFile(eq(1L), eq(5L), eq("src/A.java"));
        verify(repoFileWriteService, never()).writeFile(any(), any());
        verify(repoFileWriteService, never()).createFile(any(), any());
        assertThat(target.getStatus()).isEqualTo(FileChangeLogEntity.STATUS_APPLIED);
    }

    // ——————————— K P1: branchId 过滤 ———————————

    private FileChangeLogEntity changeWithBranch(long id, long repoId, String path,
            String oldC, String newC, String status, String branchId) {
        FileChangeLogEntity e = change(id, repoId, path, oldC, newC, status);
        e.setBranchId(branchId);
        return e;
    }

    @Test
    void applyAll_withBranchId_appliesOnlyMatchingBranchProposed() {
        // mapper 模拟 DB 过滤结果：branchId="v1" 时仅返回 v1 的 PROPOSED 条目
        FileChangeLogEntity v1 = changeWithBranch(80L, 5L, "src/V1.java", "OLD", "NEW",
                FileChangeLogEntity.STATUS_PROPOSED, "v1");
        when(fileChangeLogMapper.selectList(any())).thenReturn(List.of(v1));

        List<FileChangeVO> vos = service.applyAll(1L, 5L, 9L, "v1", false);

        // 只处理 v1 的一条 PROPOSED
        verify(repoFileWriteService, times(1)).writeFile(eq(1L), any(FileWriteRequest.class));
        assertThat(v1.getStatus()).isEqualTo(FileChangeLogEntity.STATUS_APPLIED);
        assertThat(vos).hasSize(1);
        assertThat(vos.get(0).getChangeId()).isEqualTo(80L);
    }

    @Test
    void applyAll_withNullBranchId_appliesAll() {
        // branchId=null → 不过滤分支，apply 该 session 下全部 PROPOSED（向后兼容路径）
        FileChangeLogEntity a = changeWithBranch(81L, 5L, "src/A.java", "OA", "NA",
                FileChangeLogEntity.STATUS_PROPOSED, "v1");
        FileChangeLogEntity b = changeWithBranch(82L, 5L, "src/B.java", "OB", "NB",
                FileChangeLogEntity.STATUS_PROPOSED, "v2");
        when(fileChangeLogMapper.selectList(any())).thenReturn(List.of(a, b));

        List<FileChangeVO> vos = service.applyAll(1L, 5L, 9L, (String) null, false);

        verify(repoFileWriteService, times(2)).writeFile(eq(1L), any(FileWriteRequest.class));
        assertThat(a.getStatus()).isEqualTo(FileChangeLogEntity.STATUS_APPLIED);
        assertThat(b.getStatus()).isEqualTo(FileChangeLogEntity.STATUS_APPLIED);
        assertThat(vos).hasSize(2);
    }
}
