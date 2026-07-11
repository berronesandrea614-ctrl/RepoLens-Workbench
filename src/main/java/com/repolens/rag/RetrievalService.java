package com.repolens.rag;

import com.repolens.domain.vo.CodeReferenceVO;

import java.util.List;

public interface RetrievalService {

    List<CodeReferenceVO> retrieve(Long userId, Long repoId, String query, int topK);
}
