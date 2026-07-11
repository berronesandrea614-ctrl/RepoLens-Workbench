package com.repolens.security;

/**
 * RepoLens 后端权限校验入口。
 * 当前阶段不做登录注册和复杂 RBAC，统一依赖请求头里的 X-User-Id，
 * 再结合 workspace_member 和 repo.workspace_id 做真实成员校验。
 */
public interface PermissionService {

    /**
     * 校验用户是否属于指定 workspace。
     * 当前规则很简单：只要在 workspace_member 里存在成员记录，就允许访问。
     */
    boolean checkWorkspacePermission(Long userId, Long workspaceId);

    /**
     * 校验用户是否有指定 repo 的访问权限。
     * repo 权限不单独建表，而是通过 repo.workspace_id 回落到 workspace 成员关系。
     */
    boolean checkRepoPermission(Long userId, Long repoId);
}
