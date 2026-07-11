package com.repolens.service.impl;

import com.repolens.domain.entity.WorkspaceEntity;
import com.repolens.mapper.WorkspaceMapper;
import com.repolens.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WorkspaceServiceImpl implements WorkspaceService {

    private final WorkspaceMapper workspaceMapper;

    @Override
    public WorkspaceEntity getById(Long workspaceId) {
        return workspaceMapper.selectById(workspaceId);
    }

    @Override
    public boolean exists(Long workspaceId) {
        return getById(workspaceId) != null;
    }
}
