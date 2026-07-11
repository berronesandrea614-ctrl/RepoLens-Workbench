package com.repolens.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.AdrEntity;
import com.repolens.domain.entity.RequirementEntity;
import com.repolens.domain.vo.AdrVO;
import com.repolens.mapper.AdrMapper;
import com.repolens.mapper.AgentRunPlanMapper;
import com.repolens.mapper.FileChangeLogMapper;
import com.repolens.mapper.RepoMapper;
import com.repolens.mapper.RequirementMapper;
import com.repolens.service.impl.support.AdrCrystallizer;
import com.repolens.service.impl.support.AdrSupersedeChecker;
import com.repolens.service.support.RepoWorkspaceResolver;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Service-level test for the auto-supersede trigger in generateFromRequirement (Task 4).
 *
 * <p>Tests that when AdrSupersedeChecker returns supersedes=true for an existing ACCEPTED ADR,
 * that ADR gets marked SUPERSEDED — and the new ADR is still returned successfully.
 */
@ExtendWith(MockitoExtension.class)
class AdrServiceImplSupersedeTest {

    @Mock private AdrMapper adrMapper;
    @Mock private RequirementMapper requirementMapper;
    @Mock private AgentRunPlanMapper agentRunPlanMapper;
    @Mock private FileChangeLogMapper fileChangeLogMapper;
    @Mock private RepoMapper repoMapper;
    @Mock private AdrCrystallizer crystallizer;
    @Mock private AdrSupersedeChecker supersedeChecker;
    @Mock private RepoWorkspaceResolver workspaceResolver;
    @Mock private PlatformTransactionManager txManager;

    private AdrServiceImpl service;

    @BeforeEach
    void setup() {
        service = new AdrServiceImpl(
                adrMapper, requirementMapper, agentRunPlanMapper, fileChangeLogMapper,
                repoMapper, crystallizer, supersedeChecker, workspaceResolver, txManager,
                new ObjectMapper());
        // @PostConstruct not called in unit tests — txTemplate null is fine here because
        // generateFromRequirement does not use it (only accept does).
    }

    /**
     * When checker returns supersedes=true for an existing ACCEPTED ADR:
     * <ul>
     *   <li>The old ADR is updated to SUPERSEDED with supersededBy=newAdrId</li>
     *   <li>generateFromRequirement still returns the new PROPOSED ADR (fail-safe)</li>
     * </ul>
     */
    @Test
    void generateFromRequirement_autoTriggerSupersedes_marksOldAdrSuperseded() {
        Long userId = 1L;
        Long repoId = 10L;
        Long requirementId = 5L;
        Long oldAdrId = 99L;

        // Requirement stub
        RequirementEntity requirement = new RequirementEntity();
        requirement.setId(requirementId);
        requirement.setRepoId(repoId);
        requirement.setUserId(userId);
        requirement.setApproach("Use Redis for distributed caching");
        when(requirementMapper.selectById(requirementId)).thenReturn(requirement);

        // Crystallizer returns a valid draft
        AdrCrystallizer.AdrDraft draft = new AdrCrystallizer.AdrDraft(
                "Use Redis for distributed caching",
                "We need a distributed cache",
                "Adopt Redis as the cache layer",
                "Faster reads; external dependency",
                List.of("performance"), List.of("Redis", "Memcached"), false);
        when(crystallizer.crystallize(any())).thenReturn(draft);

        // adrMapper.insert sets the ID on the entity (simulate auto-increment)
        doAnswer(invocation -> {
            AdrEntity adr = invocation.getArgument(0);
            adr.setId(100L);
            return 1;
        }).when(adrMapper).insert(any(AdrEntity.class));

        // adrMapper.selectList (ACCEPTED ADRs query) returns one old accepted ADR
        AdrEntity oldAdr = new AdrEntity();
        oldAdr.setId(oldAdrId);
        oldAdr.setRepoId(repoId);
        oldAdr.setUserId(userId);
        oldAdr.setTitle("Use in-memory map for caching");
        oldAdr.setDecision("Adopt ConcurrentHashMap as in-process cache");
        oldAdr.setStatus("ACCEPTED");
        oldAdr.setCreatedAt(LocalDateTime.now().minusDays(5));
        oldAdr.setUpdatedAt(LocalDateTime.now().minusDays(5));
        when(adrMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(oldAdr));

        // Checker returns SUPERSEDES for the old ADR
        when(supersedeChecker.check(
                "Use Redis for distributed caching", "Adopt Redis as the cache layer",
                "Use in-memory map for caching", "Adopt ConcurrentHashMap as in-process cache"))
                .thenReturn(new AdrSupersedeChecker.Verdict(true, "Redis replaces in-memory map", false));

        // Execute
        AdrVO result = service.generateFromRequirement(userId, repoId, requirementId);

        // New ADR is still returned (fail-safe: auto-trigger must not block generation)
        Assertions.assertNotNull(result, "generateFromRequirement must return new ADR regardless");
        Assertions.assertEquals("PROPOSED", result.getStatus());
        Assertions.assertEquals("Use Redis for distributed caching", result.getTitle());

        // Old ADR was updated to SUPERSEDED with supersededBy=newAdrId
        ArgumentCaptor<AdrEntity> updateCaptor = ArgumentCaptor.forClass(AdrEntity.class);
        verify(adrMapper).updateById(updateCaptor.capture());
        AdrEntity updated = updateCaptor.getValue();
        Assertions.assertEquals(oldAdrId, updated.getId());
        Assertions.assertEquals("SUPERSEDED", updated.getStatus());
        Assertions.assertEquals(100L, updated.getSupersededBy(),
                "supersededBy must equal the new ADR id");
    }

    /**
     * An ADR cannot supersede itself — BAD_REQUEST is thrown before any DB access.
     */
    @Test
    void supersede_selfReference_throwsBadRequest() {
        Long userId = 1L;
        Long repoId = 10L;
        Long adrId = 5L;

        BizException ex = Assertions.assertThrows(BizException.class,
                () -> service.supersede(userId, repoId, adrId, adrId));
        Assertions.assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
        Assertions.assertEquals("cannot supersede itself", ex.getMessage());
    }

    /**
     * When checker returns INDEPENDENT (no supersede), no updateById is called for the old ADR.
     * The new ADR is still returned normally.
     */
    @Test
    void generateFromRequirement_autoTriggerIndependent_doesNotMarkSuperseded() {
        Long userId = 1L;
        Long repoId = 10L;
        Long requirementId = 5L;

        RequirementEntity requirement = new RequirementEntity();
        requirement.setId(requirementId);
        requirement.setRepoId(repoId);
        requirement.setUserId(userId);
        requirement.setApproach("Use Kafka for async messaging");
        when(requirementMapper.selectById(requirementId)).thenReturn(requirement);

        AdrCrystallizer.AdrDraft draft = new AdrCrystallizer.AdrDraft(
                "Use Kafka for async messaging",
                "Need async event streaming",
                "Adopt Kafka",
                "Reliable async; operational overhead",
                List.of("reliability"), List.of("Kafka", "RabbitMQ"), false);
        when(crystallizer.crystallize(any())).thenReturn(draft);

        doAnswer(invocation -> {
            AdrEntity adr = invocation.getArgument(0);
            adr.setId(101L);
            return 1;
        }).when(adrMapper).insert(any(AdrEntity.class));

        AdrEntity oldAdr = new AdrEntity();
        oldAdr.setId(88L);
        oldAdr.setRepoId(repoId);
        oldAdr.setUserId(userId);
        oldAdr.setTitle("Use PostgreSQL for persistence");
        oldAdr.setDecision("Adopt PostgreSQL as primary store");
        oldAdr.setStatus("ACCEPTED");
        oldAdr.setCreatedAt(LocalDateTime.now().minusDays(10));
        oldAdr.setUpdatedAt(LocalDateTime.now().minusDays(10));
        when(adrMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(oldAdr));

        when(supersedeChecker.check(any(), any(), any(), any()))
                .thenReturn(new AdrSupersedeChecker.Verdict(false, "Different concerns", false));

        AdrVO result = service.generateFromRequirement(userId, repoId, requirementId);

        Assertions.assertNotNull(result);
        Assertions.assertEquals("PROPOSED", result.getStatus());

        // updateById should NOT have been called (no supersede)
        verify(adrMapper).insert(any(AdrEntity.class)); // just the new ADR insert
        // updateById should not be called at all since checker returned INDEPENDENT
        org.mockito.Mockito.verify(adrMapper, org.mockito.Mockito.never())
                .updateById(any(AdrEntity.class));
    }
}
