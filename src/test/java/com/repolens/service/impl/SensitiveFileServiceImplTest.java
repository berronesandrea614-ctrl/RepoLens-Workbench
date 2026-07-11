package com.repolens.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.CodeDependencyEntity;
import com.repolens.domain.entity.CodeFileEntity;
import com.repolens.domain.entity.CodeSymbolEntity;
import com.repolens.domain.entity.ComprehensionDebtFileEntity;
import com.repolens.domain.entity.FileChangeLogEntity;
import com.repolens.domain.entity.RepoConstraintRuleEntity;
import com.repolens.domain.entity.SensitiveFileEntity;
import com.repolens.domain.vo.SensitiveFileVO;
import com.repolens.mapper.CodeDependencyMapper;
import com.repolens.mapper.CodeFileMapper;
import com.repolens.mapper.CodeSymbolMapper;
import com.repolens.mapper.ComprehensionDebtFileMapper;
import com.repolens.mapper.FileChangeLogMapper;
import com.repolens.mapper.RepoConstraintRuleMapper;
import com.repolens.mapper.SensitiveFileMapper;
import com.repolens.security.PermissionService;
import com.repolens.service.impl.support.SensitiveFileComputer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for SensitiveFileServiceImpl (Feature I – 自动 ADR P1).
 * All DB access is mocked; no Spring context.
 *
 * <p>Scenarios:
 * 1. Happy path: 2 candidate files → computer called → 2 rows inserted with correct rankNo.
 * 2. Fail-safe: fanIn mapper throws → recompute still succeeds (fanIn degrades to 0).
 * 3. list: reads sensitiveFileMapper sorted by rank_no.
 */
class SensitiveFileServiceImplTest {

    private static final Long USER_ID = 1L;
    private static final Long REPO_ID = 42L;

    private PermissionService permissionService;
    private ComprehensionDebtFileMapper comprehensionDebtFileMapper;
    private FileChangeLogMapper fileChangeLogMapper;
    private CodeFileMapper codeFileMapper;
    private CodeSymbolMapper codeSymbolMapper;
    private CodeDependencyMapper codeDependencyMapper;
    private RepoConstraintRuleMapper repoConstraintRuleMapper;
    private SensitiveFileMapper sensitiveFileMapper;

    private SensitiveFileComputer sensitiveFileComputer;
    private SensitiveFileServiceImpl service;

    /** Captures entities as they are inserted; reused by list() stub in recompute tests. */
    private List<SensitiveFileEntity> insertedEntities;

    @BeforeEach
    void setUp() {
        insertedEntities = new ArrayList<>();
        permissionService           = mock(PermissionService.class);
        comprehensionDebtFileMapper = mock(ComprehensionDebtFileMapper.class);
        fileChangeLogMapper         = mock(FileChangeLogMapper.class);
        codeFileMapper              = mock(CodeFileMapper.class);
        codeSymbolMapper            = mock(CodeSymbolMapper.class);
        codeDependencyMapper        = mock(CodeDependencyMapper.class);
        repoConstraintRuleMapper    = mock(RepoConstraintRuleMapper.class);
        sensitiveFileMapper         = mock(SensitiveFileMapper.class);

        sensitiveFileComputer = new SensitiveFileComputer(); // real instance — pure function

        // TransactionTemplate wiring: mock PlatformTransactionManager so execute() runs inline
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(txManager.getTransaction(any())).thenReturn(txStatus);

        // Default permission stub — allow access unless overridden per test
        when(permissionService.checkRepoPermission(anyLong(), anyLong())).thenReturn(true);

        // Default mapper stubs — return empty unless overridden per test
        when(comprehensionDebtFileMapper.selectList(any())).thenReturn(List.of());
        when(fileChangeLogMapper.selectList(any())).thenReturn(List.of());
        when(codeFileMapper.selectList(any())).thenReturn(List.of());
        when(codeSymbolMapper.selectList(any())).thenReturn(List.of());
        when(codeDependencyMapper.selectList(any())).thenReturn(List.of());
        when(repoConstraintRuleMapper.selectList(any())).thenReturn(List.of());
        // Dynamic stubs: insert captures entities (assigning sequential IDs); selectList returns
        // them sorted by rankNo so that recompute's list() read-back reflects the persisted state.
        // Tests that call list() directly override sensitiveFileMapper.selectList() themselves.
        when(sensitiveFileMapper.delete(any())).thenAnswer(inv -> {
            insertedEntities.clear();
            return 0;
        });
        when(sensitiveFileMapper.insert(any(SensitiveFileEntity.class))).thenAnswer(inv -> {
            SensitiveFileEntity e = inv.getArgument(0);
            e.setId((long) (insertedEntities.size() + 1));
            insertedEntities.add(e);
            return 1;
        });
        when(sensitiveFileMapper.selectList(any())).thenAnswer(inv -> {
            List<SensitiveFileEntity> copy = new ArrayList<>(insertedEntities);
            copy.sort(Comparator.comparingInt(SensitiveFileEntity::getRankNo));
            return copy;
        });

        service = new SensitiveFileServiceImpl(
                permissionService,
                comprehensionDebtFileMapper,
                fileChangeLogMapper,
                codeFileMapper,
                codeSymbolMapper,
                codeDependencyMapper,
                repoConstraintRuleMapper,
                sensitiveFileMapper,
                sensitiveFileComputer,
                new ObjectMapper(),
                txManager);
        service.init(); // simulate @PostConstruct
    }

    // ── Test 0: permission check — unauthorized user throws FORBIDDEN ──────────

    @Test
    void recompute_noPermission_throwsForbidden() {
        when(permissionService.checkRepoPermission(USER_ID, REPO_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.recompute(USER_ID, REPO_ID))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getCode())
                        .isEqualTo(ErrorCode.FORBIDDEN.getCode()));
    }

    // ── Test 1: happy path ─────────────────────────────────────────────────────

    @Test
    void recompute_twoCandidates_rowsPersistedWithCorrectRankNo() {
        // Two candidate files from comprehension_debt_file
        ComprehensionDebtFileEntity f1 = debt(REPO_ID, "src/Alpha.java", 0.8);
        ComprehensionDebtFileEntity f2 = debt(REPO_ID, "src/Beta.java",  0.3);
        when(comprehensionDebtFileMapper.selectList(any())).thenReturn(List.of(f1, f2));

        // Churn: Alpha churned 5×, Beta 2×
        when(fileChangeLogMapper.selectList(any())).thenReturn(List.of(
                change(REPO_ID, "src/Alpha.java", FileChangeLogEntity.STATUS_APPLIED),
                change(REPO_ID, "src/Alpha.java", FileChangeLogEntity.STATUS_APPLIED),
                change(REPO_ID, "src/Alpha.java", FileChangeLogEntity.STATUS_APPLIED),
                change(REPO_ID, "src/Alpha.java", FileChangeLogEntity.STATUS_REVERTED),
                change(REPO_ID, "src/Alpha.java", FileChangeLogEntity.STATUS_APPLIED),
                change(REPO_ID, "src/Beta.java",  FileChangeLogEntity.STATUS_APPLIED),
                change(REPO_ID, "src/Beta.java",  FileChangeLogEntity.STATUS_APPLIED)
        ));

        List<SensitiveFileVO> result = service.recompute(USER_ID, REPO_ID);

        // delete + insert called (transaction executed)
        verify(sensitiveFileMapper).delete(any());
        verify(sensitiveFileMapper, atLeastOnce()).insert(any(SensitiveFileEntity.class));

        // Result is non-empty and ranks are 1-based sequential
        assertThat(result).isNotEmpty();
        for (int i = 0; i < result.size(); i++) {
            assertThat(result.get(i).getRankNo()).isEqualTo(i + 1);
        }

        // Alpha has higher aiRatio (0.8) + more churn → should be rank 1
        assertThat(result.get(0).getFilePath()).isEqualTo("src/Alpha.java");
    }

    // ── Test 2: fail-safe — fanIn gather throws ───────────────────────────────

    @Test
    void recompute_codeFileMapperThrows_fanInDegradesToZero_noException() {
        ComprehensionDebtFileEntity f1 = debt(REPO_ID, "src/Gamma.java", 0.5);
        when(comprehensionDebtFileMapper.selectList(any())).thenReturn(List.of(f1));
        // Simulate fanIn mapper failure
        when(codeFileMapper.selectList(any())).thenThrow(new RuntimeException("DB unavailable"));

        // Must not throw
        List<SensitiveFileVO> result = service.recompute(USER_ID, REPO_ID);

        assertThat(result).isNotEmpty();
        // fanIn should degrade to 0
        assertThat(result.get(0).getFanIn()).isEqualTo(0);
    }

    // ── Test 3: list reads from sensitiveFileMapper ────────────────────────────

    @Test
    void list_returnsMappedVOs_orderedByRankNo() {
        SensitiveFileEntity e1 = entity(REPO_ID, "src/A.java", 1, 80, "WARN");
        SensitiveFileEntity e2 = entity(REPO_ID, "src/B.java", 2, 60, "INFO");
        when(sensitiveFileMapper.selectList(any())).thenReturn(List.of(e1, e2));

        List<SensitiveFileVO> vos = service.list(USER_ID, REPO_ID);

        assertThat(vos).hasSize(2);
        assertThat(vos.get(0).getRankNo()).isEqualTo(1);
        assertThat(vos.get(0).getFilePath()).isEqualTo("src/A.java");
        assertThat(vos.get(0).getFinalScore()).isEqualTo(80);
        assertThat(vos.get(1).getRankNo()).isEqualTo(2);
        // constraintHit Integer→boolean: 0 → false
        assertThat(vos.get(0).getConstraintHit()).isFalse();
    }

    // ── Test 4: constraintHit → true when entity.constraintHit = 1 ───────────

    @Test
    void list_constraintHit1_returnsTrueBoolean() {
        SensitiveFileEntity e = entity(REPO_ID, "src/Legacy.java", 1, 90, "BLOCK");
        e.setConstraintHit(1);
        when(sensitiveFileMapper.selectList(any())).thenReturn(List.of(e));

        List<SensitiveFileVO> vos = service.list(USER_ID, REPO_ID);

        assertThat(vos.get(0).getConstraintHit()).isTrue();
    }

    // ── Fixtures ───────────────────────────────────────────────────────────────

    private ComprehensionDebtFileEntity debt(Long repoId, String filePath, double aiRatio) {
        ComprehensionDebtFileEntity e = new ComprehensionDebtFileEntity();
        e.setRepoId(repoId);
        e.setFilePath(filePath);
        e.setS1AiRatio(aiRatio);
        return e;
    }

    private FileChangeLogEntity change(Long repoId, String filePath, String status) {
        FileChangeLogEntity e = new FileChangeLogEntity();
        e.setRepoId(repoId);
        e.setFilePath(filePath);
        e.setStatus(status);
        return e;
    }

    private SensitiveFileEntity entity(Long repoId, String filePath, int rankNo, int score, String severity) {
        SensitiveFileEntity e = new SensitiveFileEntity();
        e.setRepoId(repoId);
        e.setFilePath(filePath);
        e.setRankNo(rankNo);
        e.setFinalScore(score);
        e.setSeverity(severity);
        e.setFanIn(0);
        e.setChurn(0);
        e.setAiRatio(0.0);
        e.setConstraintHit(0);
        e.setReason("test");
        e.setSignalJson("{\"fanIn\":0.0,\"churn\":0.0}");
        return e;
    }
}
