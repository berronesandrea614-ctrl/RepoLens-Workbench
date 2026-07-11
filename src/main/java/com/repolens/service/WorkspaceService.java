package com.repolens.service;

import com.repolens.domain.entity.WorkspaceEntity;

public interface WorkspaceService {

    WorkspaceEntity getById(Long workspaceId);

    boolean exists(Long workspaceId);
}
