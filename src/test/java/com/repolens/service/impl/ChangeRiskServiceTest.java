package com.repolens.service.impl;

import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.ChangeRiskFlagEntity;
import com.repolens.domain.entity.FileChangeLogEntity;
import com.repolens.mapper.ChangeRiskFlagMapper;
import com.repolens.mapper.FileChangeLogMapper;
import com.repolens.security.PermissionService;
import com.repolens.service.impl.support.DestructiveOpDetector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ChangeRiskServiceImpl (mock mappers + detector, no Spring context).
 */
class ChangeRiskServiceTest {

    private ChangeRiskFlagMapper changeRiskFlagMapper;
    private FileChangeLogMapper fileChangeLogMapper;
    private DestructiveOpDetector detector;
    private PermissionService permissionService;
    private ChangeRiskServiceImpl service;

    @BeforeEach
    void setup() {
        changeRiskFlagMapper = mock(ChangeRiskFlagMapper.class);
        fileChangeLogMapper = mock(FileChangeLogMapper.class);
        detector = mock(DestructiveOpDetector.class);
        permissionService = mock(PermissionService.class);
        // Default: has permission
        when(permissionService.checkRepoPermission(anyLong(), anyLong())).thenReturn(true);
        service = new ChangeRiskServiceImpl(changeRiskFlagMapper, fileChangeLogMapper, detector, permissionService);
    }

    // ── scanAndPersist: delete-before-insert ─────────────────────────────────

    @Test
    void scanAndPersist_withFindings_deleteThenInsert() {
        FileChangeLogEntity change = makeChange("WRITE", "/src/Main.java", "old",
                "DROP TABLE users;", 1L, 10L);
        when(fileChangeLogMapper.selectById(42L)).thenReturn(change);

        DestructiveOpDetector.RiskFinding finding = new DestructiveOpDetector.RiskFinding(
                "DESTRUCTIVE", "DROP_TABLE_DB", "BLOCK", "IRREVERSIBLE", "DROP TABLE users;");
        when(detector.detect(any(), any(), any(), any())).thenReturn(List.of(finding));

        service.scanAndPersist(1L, 42L);

        // delete always called once; insert called once per finding
        verify(changeRiskFlagMapper, times(1)).deleteByChangeId(any());
        verify(changeRiskFlagMapper, times(1)).insert((ChangeRiskFlagEntity) any());
    }

    @Test
    void scanAndPersist_noFindings_onlyDeleteCalled() {
        FileChangeLogEntity change = makeChange("WRITE", "/src/Main.java", "old", "clean", 1L, 10L);
        when(fileChangeLogMapper.selectById(42L)).thenReturn(change);
        when(detector.detect(any(), any(), any(), any())).thenReturn(List.of());

        service.scanAndPersist(1L, 42L);

        verify(changeRiskFlagMapper, times(1)).deleteByChangeId(any());
        verify(changeRiskFlagMapper, never()).insert((ChangeRiskFlagEntity) any());
    }

    @Test
    void scanAndPersist_multipleFindings_insertsAll() {
        FileChangeLogEntity change = makeChange("WRITE", "/sql/V1__init.sql", "old",
                "DROP TABLE a;\nTRUNCATE b;", 1L, 10L);
        when(fileChangeLogMapper.selectById(99L)).thenReturn(change);

        DestructiveOpDetector.RiskFinding f1 = new DestructiveOpDetector.RiskFinding(
                "DESTRUCTIVE", "DROP_TABLE_DB", "BLOCK", "IRREVERSIBLE", "DROP TABLE a;");
        DestructiveOpDetector.RiskFinding f2 = new DestructiveOpDetector.RiskFinding(
                "DESTRUCTIVE", "TRUNCATE", "BLOCK", "IRREVERSIBLE", "TRUNCATE b;");
        when(detector.detect(any(), any(), any(), any())).thenReturn(List.of(f1, f2));

        service.scanAndPersist(1L, 99L);

        verify(changeRiskFlagMapper, times(1)).deleteByChangeId(any());
        verify(changeRiskFlagMapper, times(2)).insert((ChangeRiskFlagEntity) any());
    }

    // ── triggerAsyncDetect: exception silenced ────────────────────────────────

    @Test
    void triggerAsyncDetect_exceptionSilenced_doesNotThrow() throws InterruptedException {
        when(fileChangeLogMapper.selectById(any())).thenThrow(new RuntimeException("db down"));

        // The call itself must not throw
        assertThatNoException().isThrownBy(() -> service.triggerAsyncDetect(1L, 10L, 99L));

        // Allow the async task to run and silently fail
        TimeUnit.MILLISECONDS.sleep(300);
        // Reaching here without exception = pass
    }

    @Test
    void triggerAsyncDetect_nullChangeId_silenced() {
        assertThatNoException().isThrownBy(() -> service.triggerAsyncDetect(1L, 10L, null));
    }

    // ── getUnacknowledgedBlockers ─────────────────────────────────────────────

    @Test
    void getUnacknowledgedBlockers_returnsMatchingFlags() {
        ChangeRiskFlagEntity flag = new ChangeRiskFlagEntity();
        flag.setChangeId(1L);
        flag.setSeverity("BLOCK");
        flag.setAcknowledged(0);
        when(changeRiskFlagMapper.selectList(any())).thenReturn(List.of(flag));

        List<ChangeRiskFlagEntity> result = service.getUnacknowledgedBlockers(1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSeverity()).isEqualTo("BLOCK");
        assertThat(result.get(0).getAcknowledged()).isEqualTo(0);
    }

    @Test
    void getUnacknowledgedBlockers_emptyWhenNone() {
        when(changeRiskFlagMapper.selectList(any())).thenReturn(List.of());

        List<ChangeRiskFlagEntity> result = service.getUnacknowledgedBlockers(99L);

        assertThat(result).isEmpty();
    }

    // ── acknowledge ──────────────────────────────────────────────────────────

    @Test
    void acknowledge_marksAllFlagsForChange() {
        FileChangeLogEntity change = makeChange("WRITE", "/src/A.java", "old", "new", 1L, 10L);
        when(fileChangeLogMapper.selectById(42L)).thenReturn(change);

        service.acknowledge(100L, 1L, 42L);

        verify(changeRiskFlagMapper, times(1)).acknowledgeByChangeId(eq(100L), eq(42L), any());
    }

    @Test
    void acknowledge_repoMismatch_throwsBizException() {
        FileChangeLogEntity change = makeChange("WRITE", "/src/A.java", "old", "new", 2L, 10L);
        when(fileChangeLogMapper.selectById(42L)).thenReturn(change);

        assertThrows(BizException.class, () -> service.acknowledge(100L, 1L, 42L));

        verify(changeRiskFlagMapper, never()).acknowledgeByChangeId(any(), any(), any());
    }

    @Test
    void acknowledge_changeNotFound_throwsBizException() {
        when(fileChangeLogMapper.selectById(99L)).thenReturn(null);

        assertThrows(BizException.class, () -> service.acknowledge(100L, 1L, 99L));
    }

    // ── detectSync: no DB write ───────────────────────────────────────────────

    @Test
    void detectSync_returnsEntitiesWithoutPersisting() {
        FileChangeLogEntity change = makeChange("WRITE", "/src/drop.sql", "old",
                "DROP TABLE users;", 1L, 10L);
        when(fileChangeLogMapper.selectById(42L)).thenReturn(change);

        DestructiveOpDetector.RiskFinding finding = new DestructiveOpDetector.RiskFinding(
                "DESTRUCTIVE", "DROP_TABLE_DB", "BLOCK", "IRREVERSIBLE", "DROP TABLE users;");
        when(detector.detect(any(), any(), any(), any())).thenReturn(List.of(finding));

        List<ChangeRiskFlagEntity> result = service.detectSync(1L, 42L);

        // No DB writes
        verify(changeRiskFlagMapper, never()).insert((ChangeRiskFlagEntity) any());
        verify(changeRiskFlagMapper, never()).deleteByChangeId(any());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSeverity()).isEqualTo("BLOCK");
        assertThat(result.get(0).getRuleCode()).isEqualTo("DROP_TABLE_DB");
        assertThat(result.get(0).getAcknowledged()).isEqualTo(0);
    }

    @Test
    void detectSync_changeNotFound_returnsEmpty() {
        when(fileChangeLogMapper.selectById(999L)).thenReturn(null);

        List<ChangeRiskFlagEntity> result = service.detectSync(1L, 999L);

        assertThat(result).isEmpty();
        verify(detector, never()).detect(any(), any(), any(), any());
    }

    // ── permission: listBySession ─────────────────────────────────────────────

    @Test
    void listBySession_noPermission_throwsForbidden() {
        when(permissionService.checkRepoPermission(eq(99L), eq(1L))).thenReturn(false);

        assertThatThrownBy(() -> service.listBySession(99L, 1L, 10L))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN.getCode()));

        // Must not touch the DB when forbidden
        verify(fileChangeLogMapper, never()).selectList(any());
    }

    @Test
    void listBySession_withPermission_queriesDb() {
        when(permissionService.checkRepoPermission(eq(1L), eq(1L))).thenReturn(true);
        when(fileChangeLogMapper.selectList(any())).thenReturn(List.of());

        List<?> result = service.listBySession(1L, 1L, 10L);

        assertThat(result).isEmpty();
        verify(fileChangeLogMapper, times(1)).selectList(any());
    }

    // ── permission: acknowledge ───────────────────────────────────────────────

    @Test
    void acknowledge_noPermission_throwsForbidden() {
        when(permissionService.checkRepoPermission(eq(99L), eq(1L))).thenReturn(false);

        assertThatThrownBy(() -> service.acknowledge(99L, 1L, 42L))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN.getCode()));

        // Must not touch the DB when forbidden
        verify(fileChangeLogMapper, never()).selectById(any());
        verify(changeRiskFlagMapper, never()).acknowledgeByChangeId(any(), any(), any());
    }

    @Test
    void acknowledge_withPermission_repoMismatch_throwsNotFound() {
        when(permissionService.checkRepoPermission(eq(1L), eq(1L))).thenReturn(true);
        // change belongs to repo 2, not repo 1
        FileChangeLogEntity change = makeChange("WRITE", "/src/A.java", "old", "new", 2L, 10L);
        when(fileChangeLogMapper.selectById(42L)).thenReturn(change);

        assertThatThrownBy(() -> service.acknowledge(1L, 1L, 42L))
                .isInstanceOf(BizException.class);

        verify(changeRiskFlagMapper, never()).acknowledgeByChangeId(any(), any(), any());
    }

    // ── helper ───────────────────────────────────────────────────────────────

    private FileChangeLogEntity makeChange(String opType, String filePath,
                                           String oldContent, String newContent,
                                           Long repoId, Long sessionId) {
        FileChangeLogEntity e = new FileChangeLogEntity();
        e.setOpType(opType);
        e.setFilePath(filePath);
        e.setOldContent(oldContent);
        e.setNewContent(newContent);
        e.setRepoId(repoId);
        e.setSessionId(sessionId);
        return e;
    }
}
