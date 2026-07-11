package com.repolens.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.repolens.domain.entity.WorkspaceMemberEntity;
import com.repolens.domain.enums.WorkspaceRole;
import com.repolens.mapper.WorkspaceMemberMapper;
import com.repolens.service.WorkspaceMemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkspaceMemberServiceImpl implements WorkspaceMemberService {

    private final WorkspaceMemberMapper workspaceMemberMapper;

    @Override
    public boolean isMember(Long workspaceId, Long userId) {
        return workspaceMemberMapper.selectCount(Wrappers.<WorkspaceMemberEntity>lambdaQuery()
                .eq(WorkspaceMemberEntity::getWorkspaceId, workspaceId)
                .eq(WorkspaceMemberEntity::getUserId, userId)) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkspaceMemberEntity addMember(Long workspaceId, Long userId, WorkspaceRole role) {
        WorkspaceMemberEntity member = workspaceMemberMapper.selectOne(Wrappers.<WorkspaceMemberEntity>lambdaQuery()
                .eq(WorkspaceMemberEntity::getWorkspaceId, workspaceId)
                .eq(WorkspaceMemberEntity::getUserId, userId)
                .last("LIMIT 1"));
        if (member != null) {
            return member;
        }

        WorkspaceMemberEntity entity = new WorkspaceMemberEntity();
        entity.setWorkspaceId(workspaceId);
        entity.setUserId(userId);
        entity.setRole(role);
        workspaceMemberMapper.insert(entity);
        return entity;
    }
}
