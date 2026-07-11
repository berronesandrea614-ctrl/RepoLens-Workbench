package com.repolens.service;

import com.repolens.domain.vo.CodeGraphVO;

public interface CodeGraphService {
    CodeGraphVO buildGraph(Long userId, Long repoId, Long rootSymbolId,
                           String direction, int depth, double minConfidence);
}
