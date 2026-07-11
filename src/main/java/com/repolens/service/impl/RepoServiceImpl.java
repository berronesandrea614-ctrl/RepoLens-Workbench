package com.repolens.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.common.util.RepoUrlValidator;
import com.repolens.domain.dto.repo.CreateRepoRequest;
import com.repolens.domain.dto.repo.ReindexRequest;
import com.repolens.domain.entity.ChatMessageEntity;
import com.repolens.domain.entity.ChatSessionEntity;
import com.repolens.domain.entity.CodeChunkEntity;
import com.repolens.domain.entity.CodeDependencyEntity;
import com.repolens.domain.entity.CodeFileEntity;
import com.repolens.domain.entity.CodeSymbolEntity;
import com.repolens.domain.entity.FileChangeLogEntity;
import com.repolens.domain.entity.IndexTaskEntity;
import com.repolens.domain.entity.LlmCallLogEntity;
import com.repolens.domain.entity.RepoEntity;
import com.repolens.domain.entity.RequirementEntity;
import com.repolens.domain.entity.RequirementSymbolEntity;
import com.repolens.domain.entity.ToolCallLogEntity;
import com.repolens.domain.entity.AgentMemoryEntity;
import com.repolens.domain.entity.WorkspaceMemberEntity;
import com.repolens.domain.enums.RepoIndexStatus;
import com.repolens.domain.enums.WorkspaceRole;
import com.repolens.domain.vo.IndexTaskVO;
import com.repolens.domain.vo.RepoVO;
import com.repolens.mapper.AgentMemoryMapper;
import com.repolens.mapper.ChatMessageMapper;
import com.repolens.mapper.ChatSessionMapper;
import com.repolens.mapper.CodeChunkMapper;
import com.repolens.mapper.CodeDependencyMapper;
import com.repolens.mapper.CodeFileMapper;
import com.repolens.mapper.CodeSymbolMapper;
import com.repolens.mapper.FileChangeLogMapper;
import com.repolens.mapper.IndexTaskMapper;
import com.repolens.mapper.LlmCallLogMapper;
import com.repolens.mapper.RepoMapper;
import com.repolens.mapper.RequirementMapper;
import com.repolens.mapper.RequirementSymbolMapper;
import com.repolens.mapper.ToolCallLogMapper;
import com.repolens.mapper.WorkspaceMemberMapper;
import com.repolens.security.PermissionService;
import com.repolens.service.IndexTaskService;
import com.repolens.service.MilvusService;
import com.repolens.service.RepoService;
import com.repolens.service.support.RepoWorkspaceResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 仓库元数据服务实现。
 * 这一层负责 repo 表和“初始化任务”的协调，解决的是：
 * 1. 谁能创建 / 查看 / 重新索引仓库；
 * 2. repoUrl 和 branchName 是否合法；
 * 3. 创建仓库时如何把 repo 事实记录和 index_task 初始状态一起落库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepoServiceImpl implements RepoService {

    private final RepoMapper repoMapper;
    private final WorkspaceMemberMapper workspaceMemberMapper;
    private final IndexTaskService indexTaskService;
    private final PermissionService permissionService;
    private final RepoUrlValidator repoUrlValidator;
    private final CodeFileMapper codeFileMapper;
    private final CodeSymbolMapper codeSymbolMapper;
    private final CodeDependencyMapper codeDependencyMapper;
    private final CodeChunkMapper codeChunkMapper;
    private final IndexTaskMapper indexTaskMapper;
    private final RequirementMapper requirementMapper;
    private final RequirementSymbolMapper requirementSymbolMapper;
    private final FileChangeLogMapper fileChangeLogMapper;
    private final LlmCallLogMapper llmCallLogMapper;
    private final ToolCallLogMapper toolCallLogMapper;
    private final AgentMemoryMapper agentMemoryMapper;
    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final MilvusService milvusService;
    private final RepoWorkspaceResolver repoWorkspaceResolver;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RepoVO createRepo(Long userId, CreateRepoRequest request) {
        // 如果用户是请求 workspace 的成员则直接使用，否则自动回落到用户自己的 workspace。
        // 这样前端硬编码 workspaceId=1 对新用户无害。
        Long effectiveWorkspaceId = resolveWorkspaceId(userId, request.getWorkspaceId());

        // repoUrl 在入库前就校验，避免危险的 file:// 地址进入事实表。
        repoUrlValidator.validate(request.getRepoUrl());

        String branchName = normalizeBranchName(request.getBranchName());
        RepoEntity repo = new RepoEntity();
        repo.setWorkspaceId(effectiveWorkspaceId);
        repo.setRepoName(request.getRepoName().trim());
        repo.setRepoUrl(request.getRepoUrl().trim());
        repo.setBranchName(branchName);
        repo.setIndexStatus(RepoIndexStatus.PENDING);
        repo.setCreatedBy(userId);
        repoMapper.insert(repo);

        // 创建 repo 后立即补一条初始 clone 任务，保证同步 / 异步索引共用同一条事实来源。
        indexTaskService.createInitCloneTask(repo.getId(), branchName);
        return toRepoVO(loadRepoOrThrow(repo.getId()));
    }

    @Override
    public List<RepoVO> listRepos(Long userId) {
        if (userId == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "userId is required");
        }
        // 访问边界与 checkRepoPermission 保持一致：repo 权限回落为 workspace 成员关系。
        // 先取用户所属的全部 workspaceId，再一次性拉出这些 workspace 下的仓库。
        List<WorkspaceMemberEntity> memberships = workspaceMemberMapper.selectList(
                Wrappers.<WorkspaceMemberEntity>lambdaQuery()
                        .eq(WorkspaceMemberEntity::getUserId, userId));
        Set<Long> workspaceIds = memberships.stream()
                .map(WorkspaceMemberEntity::getWorkspaceId)
                .collect(Collectors.toSet());
        if (workspaceIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<RepoEntity> repos = repoMapper.selectList(
                Wrappers.<RepoEntity>lambdaQuery()
                        .in(RepoEntity::getWorkspaceId, workspaceIds)
                        .orderByDesc(RepoEntity::getCreatedAt));
        return repos.stream().map(this::toRepoVO).collect(Collectors.toList());
    }

    @Override
    public RepoVO getRepo(Long userId, Long repoId) {
        ensureRepoPermission(userId, repoId);
        return toRepoVO(loadRepoOrThrow(repoId));
    }

    @Override
    public List<IndexTaskVO> listRepoTasks(Long userId, Long repoId) {
        ensureRepoPermission(userId, repoId);
        loadRepoOrThrow(repoId);
        return indexTaskService.listByRepoId(repoId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IndexTaskVO reindexRepo(Long userId, Long repoId, ReindexRequest request) {
        ensureRepoPermission(userId, repoId);
        RepoEntity repo = loadRepoOrThrow(repoId);

        String requestBranch = request == null ? null : request.getBranchName();
        String requestId = request == null ? null : request.getRequestId();
        String branchName = StringUtils.hasText(requestBranch)
                ? normalizeBranchName(requestBranch)
                : normalizeBranchName(repo.getBranchName());

        IndexTaskEntity task = indexTaskService.createReindexCloneTask(repoId, branchName, requestId);
        return toTaskVO(task);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRepo(Long userId, Long repoId) {
        ensureRepoPermission(userId, repoId);
        RepoEntity repo = loadRepoOrThrow(repoId);

        // 先删掉以 requirementId 为外键的关联行（requirement_symbol），再删 requirement 本体。
        List<Long> requirementIds = requirementMapper.selectList(
                        Wrappers.<RequirementEntity>lambdaQuery().eq(RequirementEntity::getRepoId, repoId))
                .stream().map(RequirementEntity::getId).collect(Collectors.toList());
        if (!requirementIds.isEmpty()) {
            requirementSymbolMapper.delete(Wrappers.<RequirementSymbolEntity>lambdaQuery()
                    .in(RequirementSymbolEntity::getRequirementId, requirementIds));
        }
        requirementMapper.delete(Wrappers.<RequirementEntity>lambdaQuery()
                .eq(RequirementEntity::getRepoId, repoId));

        // 先删掉以 sessionId 为外键的消息（chat_message），再删 chat_session 本体。
        List<Long> sessionIds = chatSessionMapper.selectList(
                        Wrappers.<ChatSessionEntity>lambdaQuery().eq(ChatSessionEntity::getRepoId, repoId))
                .stream().map(ChatSessionEntity::getId).collect(Collectors.toList());
        if (!sessionIds.isEmpty()) {
            chatMessageMapper.delete(Wrappers.<ChatMessageEntity>lambdaQuery()
                    .in(ChatMessageEntity::getSessionId, sessionIds));
        }
        chatSessionMapper.delete(Wrappers.<ChatSessionEntity>lambdaQuery()
                .eq(ChatSessionEntity::getRepoId, repoId));

        // 其余直接以 repoId 为外键的事实表，逐张全量清理。
        codeFileMapper.delete(Wrappers.<CodeFileEntity>lambdaQuery()
                .eq(CodeFileEntity::getRepoId, repoId));
        codeSymbolMapper.delete(Wrappers.<CodeSymbolEntity>lambdaQuery()
                .eq(CodeSymbolEntity::getRepoId, repoId));
        codeDependencyMapper.delete(Wrappers.<CodeDependencyEntity>lambdaQuery()
                .eq(CodeDependencyEntity::getRepoId, repoId));
        codeChunkMapper.delete(Wrappers.<CodeChunkEntity>lambdaQuery()
                .eq(CodeChunkEntity::getRepoId, repoId));
        indexTaskMapper.delete(Wrappers.<IndexTaskEntity>lambdaQuery()
                .eq(IndexTaskEntity::getRepoId, repoId));
        fileChangeLogMapper.delete(Wrappers.<FileChangeLogEntity>lambdaQuery()
                .eq(FileChangeLogEntity::getRepoId, repoId));
        llmCallLogMapper.delete(Wrappers.<LlmCallLogEntity>lambdaQuery()
                .eq(LlmCallLogEntity::getRepoId, repoId));
        toolCallLogMapper.delete(Wrappers.<ToolCallLogEntity>lambdaQuery()
                .eq(ToolCallLogEntity::getRepoId, repoId));
        agentMemoryMapper.delete(Wrappers.<AgentMemoryEntity>lambdaQuery()
                .eq(AgentMemoryEntity::getRepoId, repoId));

        // 最后删 repo 本体。以上均在同一事务内，任一失败整体回滚。
        repoMapper.deleteById(repoId);

        // 事务提交成功后再清理外部资源（Milvus 向量 + 磁盘工作副本），best-effort，不参与 DB 回滚。
        scheduleExternalCleanup(repo);
    }

    /**
     * 把 Milvus / 磁盘清理推迟到事务提交之后执行：
     * DB 才是事实数据源，只有确认元数据删除已落库，才动外部索引与磁盘副本。
     * 单测等无事务上下文时直接内联执行。
     */
    private void scheduleExternalCleanup(RepoEntity repo) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cleanupExternalResources(repo);
                }
            });
        } else {
            cleanupExternalResources(repo);
        }
    }

    private void cleanupExternalResources(RepoEntity repo) {
        Long repoId = repo.getId();
        try {
            milvusService.deleteByRepoId(repoId);
        } catch (Exception ex) {
            log.warn("Milvus cleanup failed during repo delete, repoId={}, reason={}", repoId, ex.getMessage());
        }
        try {
            deleteWorkingCopy(repo);
        } catch (Exception ex) {
            log.warn("Disk working-copy cleanup failed during repo delete, repoId={}, reason={}",
                    repoId, ex.getMessage());
        }
    }

    /**
     * 删除磁盘工作副本目录。路径由 RepoWorkspaceResolver 解析并强制约束在 repo-storage-root 之内；
     * 目录不存在时 resolver 抛 NOT_FOUND，视作已无残留、静默跳过。
     */
    private void deleteWorkingCopy(RepoEntity repo) throws IOException {
        Path repoDirectory;
        try {
            repoDirectory = repoWorkspaceResolver.resolveRepoDirectory(repo);
        } catch (BizException ex) {
            log.debug("No working copy to delete for repoId={}, reason={}", repo.getId(), ex.getMessage());
            return;
        }
        try (var paths = Files.walk(repoDirectory)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ex) {
                            log.warn("Failed to delete path during repo working-copy cleanup, path={}, reason={}",
                                    path, ex.getMessage());
                        }
                    });
        }
    }

    /**
     * 解析创建 repo 时应挂载的 workspace。
     * 若用户是 requestedWorkspaceId 的成员则直接使用；否则回落到用户自己的 workspace（优先 OWNER 角色）。
     * 若用户没有任何 workspace 则抛 FORBIDDEN（正常情况下注册时就会创建，此分支不应触发）。
     */
    private Long resolveWorkspaceId(Long userId, Long requestedWorkspaceId) {
        if (requestedWorkspaceId != null
                && permissionService.checkWorkspacePermission(userId, requestedWorkspaceId)) {
            return requestedWorkspaceId;
        }
        // Fall back: find the user's own workspace, preferring the one they own.
        List<WorkspaceMemberEntity> memberships = workspaceMemberMapper.selectList(
                Wrappers.<WorkspaceMemberEntity>lambdaQuery()
                        .eq(WorkspaceMemberEntity::getUserId, userId));
        return memberships.stream()
                .sorted(Comparator.comparingInt(
                        (WorkspaceMemberEntity m) -> WorkspaceRole.OWNER.equals(m.getRole()) ? 0 : 1))
                .map(WorkspaceMemberEntity::getWorkspaceId)
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.FORBIDDEN, "No workspace permission"));
    }

    /**
     * repo 级权限是后端真正的访问边界，Controller 只负责把 X-User-Id 透传下来。
     */
    private void ensureRepoPermission(Long userId, Long repoId) {
        if (!permissionService.checkRepoPermission(userId, repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "No repo permission");
        }
    }

    private String normalizeBranchName(String branchName) {
        return StringUtils.hasText(branchName) ? branchName.trim() : "main";
    }

    private RepoEntity loadRepoOrThrow(Long repoId) {
        RepoEntity repo = repoMapper.selectById(repoId);
        if (repo == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "Repo not found");
        }
        return repo;
    }

    private RepoVO toRepoVO(RepoEntity repo) {
        return RepoVO.builder()
                .id(repo.getId())
                .workspaceId(repo.getWorkspaceId())
                .repoName(repo.getRepoName())
                .repoUrl(repo.getRepoUrl())
                .branchName(repo.getBranchName())
                .latestCommitId(repo.getLatestCommitId())
                .indexStatus(repo.getIndexStatus())
                .createdAt(repo.getCreatedAt())
                .updatedAt(repo.getUpdatedAt())
                .build();
    }

    private IndexTaskVO toTaskVO(IndexTaskEntity task) {
        return IndexTaskVO.builder()
                .id(task.getId())
                .repoId(task.getRepoId())
                .taskType(task.getTaskType())
                .status(task.getStatus())
                .retryCount(task.getRetryCount())
                .maxRetry(task.getMaxRetry())
                .idempotentKey(task.getIdempotentKey())
                .errorMsg(task.getErrorMsg())
                .createdAt(task.getCreatedAt())
                .updatedAt(task.getUpdatedAt())
                .build();
    }
}
