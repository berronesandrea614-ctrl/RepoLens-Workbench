package com.repolens.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repolens.common.exception.BizException;
import com.repolens.common.util.RepoUrlValidator;
import com.repolens.domain.dto.repo.CreateRepoRequest;
import com.repolens.domain.dto.repo.ReindexRequest;
import com.repolens.domain.entity.IndexTaskEntity;
import com.repolens.domain.entity.RepoEntity;
import com.repolens.domain.entity.WorkspaceMemberEntity;
import com.repolens.domain.enums.RepoIndexStatus;
import com.repolens.domain.enums.TaskStatus;
import com.repolens.domain.enums.TaskType;
import com.repolens.domain.enums.WorkspaceRole;
import com.repolens.domain.vo.IndexTaskVO;
import com.repolens.domain.vo.RepoVO;
import com.repolens.mapper.RepoMapper;
import com.repolens.mapper.WorkspaceMemberMapper;
import com.repolens.security.PermissionService;
import com.repolens.service.impl.RepoServiceImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RepoServiceImplTest {

    @Mock
    private RepoMapper repoMapper;
    @Mock
    private WorkspaceMemberMapper workspaceMemberMapper;
    @Mock
    private IndexTaskService indexTaskService;
    @Mock
    private PermissionService permissionService;
    @Mock
    private RepoUrlValidator repoUrlValidator;
    @Mock
    private com.repolens.mapper.CodeFileMapper codeFileMapper;
    @Mock
    private com.repolens.mapper.CodeSymbolMapper codeSymbolMapper;
    @Mock
    private com.repolens.mapper.CodeDependencyMapper codeDependencyMapper;
    @Mock
    private com.repolens.mapper.CodeChunkMapper codeChunkMapper;
    @Mock
    private com.repolens.mapper.IndexTaskMapper indexTaskMapper;
    @Mock
    private com.repolens.mapper.RequirementMapper requirementMapper;
    @Mock
    private com.repolens.mapper.RequirementSymbolMapper requirementSymbolMapper;
    @Mock
    private com.repolens.mapper.FileChangeLogMapper fileChangeLogMapper;
    @Mock
    private com.repolens.mapper.LlmCallLogMapper llmCallLogMapper;
    @Mock
    private com.repolens.mapper.ToolCallLogMapper toolCallLogMapper;
    @Mock
    private com.repolens.mapper.AgentMemoryMapper agentMemoryMapper;
    @Mock
    private com.repolens.mapper.ChatSessionMapper chatSessionMapper;
    @Mock
    private com.repolens.mapper.ChatMessageMapper chatMessageMapper;
    @Mock
    private com.repolens.service.MilvusService milvusService;
    @Mock
    private com.repolens.service.support.RepoWorkspaceResolver repoWorkspaceResolver;

    @InjectMocks
    private RepoServiceImpl repoService;

    @BeforeEach
    void setUp() {
        lenient().when(permissionService.checkWorkspacePermission(any(), any())).thenReturn(true);
        lenient().when(permissionService.checkRepoPermission(any(), any())).thenReturn(true);
        lenient().doNothing().when(repoUrlValidator).validate(anyString());
    }

    private static WorkspaceMemberEntity membership(Long workspaceId) {
        WorkspaceMemberEntity m = new WorkspaceMemberEntity();
        m.setWorkspaceId(workspaceId);
        return m;
    }

    private static RepoEntity repo(Long id, Long workspaceId, String name) {
        RepoEntity r = new RepoEntity();
        r.setId(id);
        r.setWorkspaceId(workspaceId);
        r.setRepoName(name);
        r.setIndexStatus(RepoIndexStatus.PENDING);
        return r;
    }

    @Test
    void listRepos_shouldReturnOnlyReposInUserWorkspaces() {
        // 用户属于 workspace 1，返回该 workspace 下的仓库；其它 workspace 的仓库被数据库层过滤掉。
        when(workspaceMemberMapper.selectList(any()))
                .thenReturn(List.of(membership(1L)));
        when(repoMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(repo(10L, 1L, "own-repo"), repo(11L, 1L, "member-repo")));

        List<RepoVO> result = repoService.listRepos(1L);

        Assertions.assertEquals(2, result.size());
        Assertions.assertEquals("own-repo", result.get(0).getRepoName());
        Assertions.assertEquals(1L, result.get(0).getWorkspaceId());
        Assertions.assertEquals(1L, result.get(1).getWorkspaceId());
    }

    @Test
    void listRepos_shouldReturnEmptyWhenUserHasNoWorkspace() {
        // 用户不属于任何 workspace，不应该看到任何仓库，也不应再查 repo 表。
        when(workspaceMemberMapper.selectList(any())).thenReturn(List.of());

        List<RepoVO> result = repoService.listRepos(2L);

        Assertions.assertTrue(result.isEmpty());
        verify(repoMapper, org.mockito.Mockito.never()).selectList(any());
    }

    @Test
    void createRepo_shouldDefaultBranchAndCreateCloneTask() {
        CreateRepoRequest request = new CreateRepoRequest();
        request.setWorkspaceId(1L);
        request.setRepoName("demo-repo");
        request.setRepoUrl("https://github.com/acme/demo-repo.git");
        request.setBranchName(" ");

        when(repoMapper.insert(any(RepoEntity.class))).thenAnswer(invocation -> {
            RepoEntity entity = invocation.getArgument(0);
            entity.setId(100L);
            return 1;
        });
        when(repoMapper.selectById(100L)).thenAnswer(invocation -> {
            RepoEntity entity = new RepoEntity();
            entity.setId(100L);
            entity.setWorkspaceId(1L);
            entity.setRepoName("demo-repo");
            entity.setRepoUrl("https://github.com/acme/demo-repo.git");
            entity.setBranchName("main");
            entity.setIndexStatus(RepoIndexStatus.PENDING);
            entity.setCreatedBy(1L);
            return entity;
        });

        RepoVO result = repoService.createRepo(1L, request);

        Assertions.assertEquals("main", result.getBranchName());
        Assertions.assertEquals(RepoIndexStatus.PENDING, result.getIndexStatus());
        verify(repoUrlValidator).validate("https://github.com/acme/demo-repo.git");
        verify(indexTaskService).createInitCloneTask(100L, "main");
    }

    @Test
    void createRepo_shouldThrowWhenRepoUrlInvalid() {
        CreateRepoRequest request = new CreateRepoRequest();
        request.setWorkspaceId(1L);
        request.setRepoName("demo-repo");
        request.setRepoUrl("ftp://invalid-url");
        request.setBranchName("main");

        doThrow(new BizException(40000, "Invalid repoUrl format")).when(repoUrlValidator).validate("ftp://invalid-url");

        Assertions.assertThrows(BizException.class, () -> repoService.createRepo(1L, request));
    }

    @Test
    void deleteRepo_shouldCascadeDeleteAllChildTablesAndMilvusWhenAuthorized() {
        RepoEntity repo = repo(10L, 1L, "doomed-repo");
        repo.setBranchName("main");
        when(repoMapper.selectById(10L)).thenReturn(repo);
        // requirement / session 子表：返回带 id 的行，触发按 id 删除子子表。
        com.repolens.domain.entity.RequirementEntity req = new com.repolens.domain.entity.RequirementEntity();
        req.setId(500L);
        req.setRepoId(10L);
        when(requirementMapper.selectList(any())).thenReturn(List.of(req));
        com.repolens.domain.entity.ChatSessionEntity sess = new com.repolens.domain.entity.ChatSessionEntity();
        sess.setId(700L);
        sess.setRepoId(10L);
        when(chatSessionMapper.selectList(any())).thenReturn(List.of(sess));
        when(repoWorkspaceResolver.resolveRepoDirectory(any()))
                .thenThrow(new BizException(40400, "Local repository not found"));

        repoService.deleteRepo(1L, 10L);

        // 每张以 repoId 为外键的事实表都要被清理。
        verify(codeFileMapper).delete(any());
        verify(codeSymbolMapper).delete(any());
        verify(codeDependencyMapper).delete(any());
        verify(codeChunkMapper).delete(any());
        verify(indexTaskMapper).delete(any());
        verify(requirementMapper).delete(any());
        verify(requirementSymbolMapper).delete(any());
        verify(fileChangeLogMapper).delete(any());
        verify(llmCallLogMapper).delete(any());
        verify(toolCallLogMapper).delete(any());
        verify(agentMemoryMapper).delete(any());
        verify(chatSessionMapper).delete(any());
        verify(chatMessageMapper).delete(any());
        verify(repoMapper).deleteById(10L);
        // 事务外资源尽力清理：Milvus 向量必删。
        verify(milvusService).deleteByRepoId(10L);
    }

    @Test
    void deleteRepo_shouldThrowForbiddenWhenNotAuthorized() {
        when(permissionService.checkRepoPermission(9L, 10L)).thenReturn(false);

        Assertions.assertThrows(BizException.class, () -> repoService.deleteRepo(9L, 10L));

        verify(repoMapper, org.mockito.Mockito.never()).deleteById(any(java.io.Serializable.class));
        verify(milvusService, org.mockito.Mockito.never()).deleteByRepoId(any());
    }

    @Test
    void reindexRepo_shouldCreatePendingReindexTask() {
        RepoEntity repo = new RepoEntity();
        repo.setId(10L);
        repo.setWorkspaceId(1L);
        repo.setBranchName("develop");
        repo.setIndexStatus(RepoIndexStatus.INDEXED);
        when(repoMapper.selectById(10L)).thenReturn(repo);

        IndexTaskEntity taskEntity = new IndexTaskEntity();
        taskEntity.setId(999L);
        taskEntity.setRepoId(10L);
        taskEntity.setTaskType(TaskType.CLONE_REPO);
        taskEntity.setStatus(TaskStatus.PENDING);
        when(indexTaskService.createReindexCloneTask(eq(10L), eq("develop"), eq("custom-req-id")))
                .thenReturn(taskEntity);

        ReindexRequest request = new ReindexRequest();
        request.setRequestId("custom-req-id");
        IndexTaskVO vo = repoService.reindexRepo(1L, 10L, request);

        Assertions.assertEquals(TaskType.CLONE_REPO, vo.getTaskType());
        Assertions.assertEquals(TaskStatus.PENDING, vo.getStatus());
    }

    @Test
    void createRepo_fallsBackToOwnWorkspaceWhenRequestedWorkspaceNotAccessible() {
        // User 2 is NOT a member of workspace 1 (the frontend's hardcoded value).
        when(permissionService.checkWorkspacePermission(2L, 1L)).thenReturn(false);

        // User 2 IS an OWNER of their own workspace (id=2, created at registration).
        WorkspaceMemberEntity ownMembership = new WorkspaceMemberEntity();
        ownMembership.setWorkspaceId(2L);
        ownMembership.setRole(WorkspaceRole.OWNER);
        when(workspaceMemberMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(ownMembership));

        CreateRepoRequest request = new CreateRepoRequest();
        request.setWorkspaceId(1L); // hardcoded frontend value — user 2 is not a member
        request.setRepoName("user-repo");
        request.setRepoUrl("https://github.com/user2/user-repo.git");
        request.setBranchName("main");

        when(repoMapper.insert(any(RepoEntity.class))).thenAnswer(invocation -> {
            RepoEntity entity = invocation.getArgument(0);
            entity.setId(200L);
            return 1;
        });
        when(repoMapper.selectById(200L)).thenAnswer(invocation -> {
            RepoEntity entity = new RepoEntity();
            entity.setId(200L);
            entity.setWorkspaceId(2L); // fell back to user's own workspace
            entity.setRepoName("user-repo");
            entity.setBranchName("main");
            entity.setIndexStatus(RepoIndexStatus.PENDING);
            return entity;
        });

        RepoVO result = repoService.createRepo(2L, request);

        // Repo must be created in the user's own workspace, not workspace 1.
        Assertions.assertEquals(2L, result.getWorkspaceId());
        Assertions.assertEquals(RepoIndexStatus.PENDING, result.getIndexStatus());
        verify(indexTaskService).createInitCloneTask(200L, "main");
    }

    @Test
    void createRepo_throwsForbiddenWhenUserHasNoWorkspaceAtAll() {
        // User 3 is not a member of the requested workspace.
        when(permissionService.checkWorkspacePermission(3L, 1L)).thenReturn(false);
        // And user 3 has no workspace memberships at all.
        when(workspaceMemberMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of());

        CreateRepoRequest request = new CreateRepoRequest();
        request.setWorkspaceId(1L);
        request.setRepoName("orphan-repo");
        request.setRepoUrl("https://github.com/user3/orphan-repo.git");

        BizException ex = Assertions.assertThrows(BizException.class,
                () -> repoService.createRepo(3L, request));
        Assertions.assertEquals("No workspace permission", ex.getMessage());
        verify(repoMapper, org.mockito.Mockito.never()).insert(any(RepoEntity.class));
    }
}
