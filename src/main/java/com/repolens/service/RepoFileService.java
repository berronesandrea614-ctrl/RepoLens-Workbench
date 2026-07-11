package com.repolens.service;

import com.repolens.domain.vo.FileTreeNodeVO;

public interface RepoFileService {
    FileTreeNodeVO listTree(Long userId, Long repoId);
}
