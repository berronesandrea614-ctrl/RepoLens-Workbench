package com.repolens.rag.impl;

import com.repolens.domain.vo.CodeReferenceVO;
import com.repolens.rag.RetrievalService;
import com.repolens.tool.ReadonlyToolService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MockRetrievalService implements RetrievalService {

    private final ReadonlyToolService readonlyToolService;

    @Override
    public List<CodeReferenceVO> retrieve(Long userId, Long repoId, String query, int topK) {
        List<CodeReferenceVO> references = readonlyToolService.searchCodeChunks(userId, repoId, query, topK);
        if (references.isEmpty()) {
            return Collections.singletonList(CodeReferenceVO.builder()
                    .filePath("N/A")
                    .className("N/A")
                    .methodName("N/A")
                    .startLine(0)
                    .endLine(0)
                    .build());
        }
        return references;
    }
}
