package com.repolens.service;

import com.repolens.domain.vo.ChangeGraphVO;

public interface ChangeGraphService {
    ChangeGraphVO getChangeGraph(Long userId, Long repoId, Long runId);
}
