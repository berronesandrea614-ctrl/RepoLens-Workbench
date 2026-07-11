package com.repolens.security;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.RepoEntity;
import com.repolens.domain.entity.WorkspaceMemberEntity;
import com.repolens.mapper.RepoMapper;
import com.repolens.mapper.WorkspaceMemberMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 基于 workspace_member 的真实权限实现。
 * 权限边界设计：
 * 1. workspace 是成员关系的归属单元；
 * 2. repo 归属于某个 workspace；
 * 3. repo 访问权限最终都回落为“当前 user 是否是该 workspace 的成员”。
 */
@Service
@RequiredArgsConstructor
public class PermissionServiceImpl implements PermissionService {

    private final WorkspaceMemberMapper workspaceMemberMapper;
    private final RepoMapper repoMapper;

    /**
     * workspace 级权限校验。
     * 当前阶段不区分 OWNER / MEMBER / VIEWER 的细粒度差异，只要存在成员记录即通过。
     */
    @Override
    public boolean checkWorkspacePermission(Long userId, Long workspaceId) {
        if (userId == null || workspaceId == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "userId and workspaceId are required");
        }
        return workspaceMemberMapper.selectCount(Wrappers.<WorkspaceMemberEntity>lambdaQuery()
                .eq(WorkspaceMemberEntity::getUserId, userId)
                .eq(WorkspaceMemberEntity::getWorkspaceId, workspaceId)) > 0;
    }

    /**
     * repo 级权限校验。
     * 先查 repo，再拿 repo.workspace_id 回落到 workspace 成员关系，
     * 这样可以保证 repo / RAG / Chat / 工具调用共用同一条权限链路。
     */
    @Override
    public boolean checkRepoPermission(Long userId, Long repoId) {
        if (userId == null || repoId == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "userId and repoId are required");
        }
        RepoEntity repo = repoMapper.selectById(repoId);
        if (repo == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "Repo not found");
        }
        return checkWorkspacePermission(userId, repo.getWorkspaceId());
    }
}
