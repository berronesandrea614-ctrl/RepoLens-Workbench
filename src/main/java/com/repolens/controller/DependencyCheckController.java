package com.repolens.controller;

import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.common.result.Result;
import com.repolens.domain.dto.DependencyCheckRequest;
import com.repolens.domain.vo.DependencyCheckVO;
import com.repolens.security.AuthUserId;
import com.repolens.security.PermissionService;
import com.repolens.service.DependencyCheckService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 依赖体检 HTTP 接口。
 *
 * <ul>
 *   <li>POST /api/repos/{repoId}/dependency-check — 对指定 changeIds 或 sessionId 执行体检并落库</li>
 *   <li>GET  /api/repos/{repoId}/dependency-check?sessionId= — 查询已落库的体检结果</li>
 * </ul>
 *
 * <p>权限门控：X-User-Id → @AuthUserId，所有端点都要求通过 PermissionService.checkRepoPermission。
 * <p>体检结果仅包含包名（不含源码/路径上下文），符合 RepoLens 隐私卖点。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/repos/{repoId}/dependency-check")
public class DependencyCheckController {

    private final DependencyCheckService dependencyCheckService;
    private final PermissionService permissionService;

    /**
     * 体检并落库。
     * <ul>
     *   <li>若 body 含非空 changeIds → 对指定变更执行体检。</li>
     *   <li>否则若 body 含 sessionId → 对该会话下全部变更执行体检。</li>
     *   <li>两者均缺失 → 返回空列表（幂等，不报错）。</li>
     * </ul>
     */
    @PostMapping
    public Result<List<DependencyCheckVO>> check(
            @AuthUserId Long userId,
            @PathVariable("repoId") Long repoId,
            @RequestBody DependencyCheckRequest request) {

        if (!permissionService.checkRepoPermission(userId, repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "No permission for repo " + repoId);
        }

        List<Long> changeIds = request.getChangeIds();
        Long sessionId = request.getSessionId();

        List<DependencyCheckVO> result;
        if (changeIds != null && !changeIds.isEmpty()) {
            result = dependencyCheckService.checkByChangeIds(repoId, sessionId, changeIds);
        } else if (sessionId != null) {
            result = dependencyCheckService.checkBySession(repoId, sessionId);
        } else {
            result = List.of();
        }
        return Result.success(result);
    }

    /**
     * 查询某会话已落库的体检记录（供前端展示，不触发新体检）。
     */
    @GetMapping
    public Result<List<DependencyCheckVO>> queryBySession(
            @AuthUserId Long userId,
            @PathVariable("repoId") Long repoId,
            @RequestParam("sessionId") Long sessionId) {

        if (!permissionService.checkRepoPermission(userId, repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "No permission for repo " + repoId);
        }
        return Result.success(dependencyCheckService.queryBySession(repoId, sessionId));
    }
}
