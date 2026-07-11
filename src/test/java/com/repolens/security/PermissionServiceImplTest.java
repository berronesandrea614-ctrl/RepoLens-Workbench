package com.repolens.security;

import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.RepoEntity;
import com.repolens.mapper.RepoMapper;
import com.repolens.mapper.WorkspaceMemberMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PermissionServiceImplTest {

    @Mock
    private WorkspaceMemberMapper workspaceMemberMapper;
    @Mock
    private RepoMapper repoMapper;

    @InjectMocks
    private PermissionServiceImpl permissionService;

    @Test
    void checkWorkspacePermission_shouldReturnTrueWhenMemberExists() {
        when(workspaceMemberMapper.selectCount(any())).thenReturn(1L);

        Assertions.assertTrue(permissionService.checkWorkspacePermission(1L, 1L));
    }

    @Test
    void checkWorkspacePermission_shouldReturnFalseWhenMemberMissing() {
        when(workspaceMemberMapper.selectCount(any())).thenReturn(0L);

        Assertions.assertFalse(permissionService.checkWorkspacePermission(999L, 1L));
    }

    @Test
    void checkRepoPermission_shouldReturnTrueWhenRepoBelongsToAccessibleWorkspace() {
        RepoEntity repo = new RepoEntity();
        repo.setId(5L);
        repo.setWorkspaceId(1L);
        when(repoMapper.selectById(5L)).thenReturn(repo);
        when(workspaceMemberMapper.selectCount(any())).thenReturn(1L);

        Assertions.assertTrue(permissionService.checkRepoPermission(1L, 5L));
    }

    @Test
    void checkRepoPermission_shouldThrowWhenRepoMissing() {
        when(repoMapper.selectById(5L)).thenReturn(null);

        BizException ex = Assertions.assertThrows(BizException.class, () -> permissionService.checkRepoPermission(1L, 5L));

        Assertions.assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }
}
