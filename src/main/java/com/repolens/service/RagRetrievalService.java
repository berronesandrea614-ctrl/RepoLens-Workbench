package com.repolens.service;

import com.repolens.domain.vo.RagSearchResultVO;

public interface RagRetrievalService {

    RagSearchResultVO retrieve(Long repoId, Long userId, String query, Integer topK);
}
