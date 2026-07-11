package com.repolens.controller;

import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.common.result.Result;
import com.repolens.security.AuthUserId;
import com.repolens.security.PermissionService;
import com.repolens.service.impl.RepoBackgroundIndexService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台（非阻塞）索引触发端点。独立于 {@code RepoController}，避免与其内核 flag 分支争用。
 *
 * <p>{@code POST /api/repos/{id}/index/background}：立即返回，索引在后台线程跑，
 * 让「导入仓库」不再卡在 import→parse→chunk→vector 全流程上。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/repos")
public class RepoBackgroundIndexController {

    private final RepoBackgroundIndexService backgroundIndexService;
    private final PermissionService permissionService;

    @PostMapping("/{id}/index/background")
    public Result<Boolean> backgroundIndex(@AuthUserId Long userId,
                                           @PathVariable("id") Long repoId) {
        if (!permissionService.checkRepoPermission(userId, repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        boolean started = backgroundIndexService.startBackgroundIndex(repoId, userId);
        return Result.success(started);
    }
}
