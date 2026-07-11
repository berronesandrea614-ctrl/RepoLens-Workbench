package com.repolens.service;

import com.repolens.domain.vo.BuildChunkResultVO;

public interface CodeChunkBuildService {

    BuildChunkResultVO buildChunks(Long repoId, Long userId);
}
