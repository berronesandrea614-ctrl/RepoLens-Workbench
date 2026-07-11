package com.repolens.service;

import com.repolens.domain.vo.VectorizeResultVO;

public interface ChunkVectorizeService {

    VectorizeResultVO vectorizeRepoChunks(Long repoId, Long userId);
}
