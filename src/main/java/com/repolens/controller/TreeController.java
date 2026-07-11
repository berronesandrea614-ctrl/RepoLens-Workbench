package com.repolens.controller;

import com.repolens.common.result.Result;
import com.repolens.domain.vo.FileTreeNodeVO;
import com.repolens.security.AuthUserId;
import com.repolens.service.RepoFileService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/repos")
public class TreeController {

    private final RepoFileService repoFileService;

    @GetMapping("/{repoId}/tree")
    public Result<FileTreeNodeVO> tree(@AuthUserId Long userId,
                                       @PathVariable("repoId") Long repoId) {
        return Result.success(repoFileService.listTree(userId, repoId));
    }
}
