package com.repolens.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.common.result.Result;
import com.repolens.domain.entity.AgentMemoryEntity;
import com.repolens.domain.entity.SensitiveFileEntity;
import com.repolens.domain.vo.AgentsMdProposalVO;
import com.repolens.domain.vo.SensitiveFileVO;
import com.repolens.mapper.AgentMemoryMapper;
import com.repolens.mapper.SensitiveFileMapper;
import com.repolens.security.AuthUserId;
import com.repolens.security.PermissionService;
import com.repolens.service.SensitiveFileService;
import com.repolens.service.impl.support.AgentRulesLoader;
import com.repolens.service.impl.support.AgentsMdProposer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Governance endpoints: sensitive-file list/recompute + AGENTS.md proposal.
 *
 * <p>Base path: {@code /api/repos/{repoId}/governance}
 *
 * <p><b>agents-md/proposal orchestration decision:</b> placed directly in the controller.
 * The three data-gathering calls (loadRules + two mapper queries) are trivial enough
 * that a dedicated service layer would add ceremony without clarity. The controller
 * delegates all logic to {@link AgentsMdProposer}; it only coordinates data fetching.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/repos/{repoId}/governance")
public class GovernanceController {

    private final SensitiveFileService sensitiveFileService;
    private final AgentRulesLoader agentRulesLoader;
    private final AgentMemoryMapper agentMemoryMapper;
    private final SensitiveFileMapper sensitiveFileMapper;
    private final AgentsMdProposer agentsMdProposer;
    private final PermissionService permissionService;

    /**
     * List persisted sensitive files for the repo.
     */
    @GetMapping("/sensitive-files")
    public Result<List<SensitiveFileVO>> listSensitiveFiles(
            @AuthUserId Long userId,
            @PathVariable Long repoId) {
        return Result.success(sensitiveFileService.list(userId, repoId));
    }

    /**
     * Recompute sensitive files from signals and persist top-30.
     */
    @PostMapping("/sensitive-files/recompute")
    public Result<List<SensitiveFileVO>> recompute(
            @AuthUserId Long userId,
            @PathVariable Long repoId) {
        return Result.success(sensitiveFileService.recompute(userId, repoId));
    }

    /**
     * Return an AGENTS.md addendum proposal (read-only, never writes to disk).
     *
     * <p>Orchestration:
     * <ol>
     *   <li>Load current AGENTS.md content via {@link AgentRulesLoader#loadRules(Long)}.</li>
     *   <li>Query CONSTRAINT-type memories for this repo.</li>
     *   <li>Query BLOCK-severity sensitive files for this repo.</li>
     *   <li>Delegate to {@link AgentsMdProposer#propose} for diff generation.</li>
     * </ol>
     */
    @GetMapping("/agents-md/proposal")
    public Result<AgentsMdProposalVO> agentsMdProposal(
            @AuthUserId Long userId,
            @PathVariable Long repoId) {

        // 0. Repo ownership check (IDOR guard — orchestration lives here, not in a service)
        if (!permissionService.checkRepoPermission(userId, repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "No repo permission");
        }

        // 1. Load current AGENTS.md (null if absent)
        String currentAgentsMd = agentRulesLoader.loadRules(repoId);

        // 2. CONSTRAINT memories for this repo
        List<AgentMemoryEntity> constraintMemories = agentMemoryMapper.selectList(
                Wrappers.<AgentMemoryEntity>lambdaQuery()
                        .eq(AgentMemoryEntity::getRepoId, repoId)
                        .eq(AgentMemoryEntity::getMemoryType, "CONSTRAINT"));

        // 3. BLOCK-severity sensitive files for this repo
        List<SensitiveFileEntity> blockFiles = sensitiveFileMapper.selectList(
                Wrappers.<SensitiveFileEntity>lambdaQuery()
                        .eq(SensitiveFileEntity::getRepoId, repoId)
                        .eq(SensitiveFileEntity::getSeverity, "BLOCK"));

        // 4. Build proposal (never writes to disk)
        AgentsMdProposer.Proposal proposal =
                agentsMdProposer.propose(repoId, currentAgentsMd, constraintMemories, blockFiles);

        AgentsMdProposalVO vo = AgentsMdProposalVO.builder()
                .currentContent(proposal.currentContent())
                .proposedContent(proposal.proposedContent())
                .diffMarkdown(proposal.diffMarkdown())
                .hasChanges(proposal.hasChanges())
                .build();

        return Result.success(vo);
    }
}
