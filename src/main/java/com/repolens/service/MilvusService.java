package com.repolens.service;

import com.repolens.domain.entity.CodeChunkEntity;
import com.repolens.domain.vo.VectorSearchHitVO;

import java.util.List;

public interface MilvusService {

    void ensureCollection();

    void deleteByRepoId(Long repoId);

    void upsertCodeChunkVector(CodeChunkEntity chunk, float[] embedding);

    void upsertCodeChunkVectors(List<CodeChunkEntity> chunks, List<float[]> embeddings);

    List<VectorSearchHitVO> search(String query, Long repoId, Integer topK);
}
