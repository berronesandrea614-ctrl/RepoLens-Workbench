package com.repolens.service;

import com.repolens.domain.entity.WorkspaceMemberEntity;
import com.repolens.domain.enums.WorkspaceRole;

public interface WorkspaceMemberService {

    boolean isMember(Long workspaceId, Long userId);

    WorkspaceMemberEntity addMember(Long workspaceId, Long userId, WorkspaceRole role);
}
