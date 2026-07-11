package com.repolens.controller;

import com.repolens.common.result.Result;
import com.repolens.domain.vo.SearchResultVO;
import com.repolens.security.AuthUserId;
import com.repolens.service.RepoSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class SearchController {

    private final RepoSearchService repoSearchService;

    @GetMapping("/api/repos/{repoId}/search")
    public Result<SearchResultVO> search(@AuthUserId Long userId,
                                         @PathVariable("repoId") Long repoId,
                                         @RequestParam("q") String q,
                                         @RequestParam(value = "caseSensitive", defaultValue = "false") boolean caseSensitive,
                                         @RequestParam(value = "offset", defaultValue = "0") int offset,
                                         @RequestParam(value = "limit", defaultValue = "100") int limit) {
        return Result.success(repoSearchService.search(userId, repoId, q, caseSensitive, offset, limit));
    }
}
