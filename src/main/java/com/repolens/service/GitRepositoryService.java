package com.repolens.service;

import com.repolens.domain.vo.ImportRepoResultVO;

public interface GitRepositoryService {

    ImportRepoResultVO importRepository(Long repoId, Long taskId, Long userId);
}
