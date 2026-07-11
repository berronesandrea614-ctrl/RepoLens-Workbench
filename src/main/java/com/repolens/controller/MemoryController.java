package com.repolens.controller;

import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.common.result.Result;
import com.repolens.domain.vo.AgentMemoryVO;
import com.repolens.security.AuthUserId;
import com.repolens.security.PermissionService;
import com.repolens.service.AgentMemoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/repos")
public class MemoryController {

    private final AgentMemoryService agentMemoryService;
    private final PermissionService permissionService;

    @GetMapping("/{repoId}/memory")
    public Result<List<AgentMemoryVO>> getMemory(@AuthUserId Long userId,
                                                   @PathVariable("repoId") Long repoId) {
        if (!permissionService.checkRepoPermission(userId, repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "No permission for repo " + repoId);
        }
        return Result.success(agentMemoryService.list(userId, repoId));
    }

    @DeleteMapping("/{repoId}/memory/{memoryId}")
    public Result<Void> deleteMemory(@AuthUserId Long userId,
                                      @PathVariable("repoId") Long repoId,
                                      @PathVariable("memoryId") Long memoryId) {
        if (!permissionService.checkRepoPermission(userId, repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "No permission for repo " + repoId);
        }
        agentMemoryService.forget(userId, repoId, memoryId);
        return Result.success(null);
    }
}
