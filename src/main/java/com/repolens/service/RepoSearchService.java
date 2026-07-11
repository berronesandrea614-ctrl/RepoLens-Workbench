package com.repolens.service;

import com.repolens.domain.vo.SearchResultVO;

public interface RepoSearchService {
    SearchResultVO search(Long userId, Long repoId, String query, boolean caseSensitive, int offset, int limit);
}
