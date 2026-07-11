package com.repolens.controller;

import com.repolens.common.result.Result;
import com.repolens.domain.vo.ChangeRiskVO;
import com.repolens.domain.vo.FileChangeDetailVO;
import com.repolens.domain.vo.FileChangeVO;
import com.repolens.security.AuthUserId;
import com.repolens.service.ChangeRiskService;
import com.repolens.service.FileChangeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 编码模式文件变更查看与回滚接口。权限门控与路径安全都在 service 层完成。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/repos/{repoId}/changes")
public class FileChangeController {

    private final FileChangeService fileChangeService;
    private final ChangeRiskService changeRiskService;

    /** 列出变更明细（可选 sessionId 过滤）。 */
    @GetMapping
    public Result<List<FileChangeDetailVO>> listChanges(
            @AuthUserId Long userId,
            @PathVariable("repoId") Long repoId,
            @RequestParam(value = "sessionId", required = false) Long sessionId) {
        return Result.success(fileChangeService.listChanges(userId, repoId, sessionId));
    }

    /** 审批通过一条 PROPOSED 变更：把 newContent 写盘并置 APPLIED（写盘门）。ack=true 时跳过 BLOCK 拦截。 */
    @PostMapping("/{changeId}/apply")
    public Result<FileChangeVO> apply(
            @AuthUserId Long userId,
            @PathVariable("repoId") Long repoId,
            @PathVariable("changeId") Long changeId,
            @RequestParam(value = "ack", required = false) boolean ack) {
        return Result.success(fileChangeService.apply(userId, repoId, changeId, ack));
    }

    /** 拒绝一条 PROPOSED 变更：仅置 REJECTED，不写盘。 */
    @PostMapping("/{changeId}/reject")
    public Result<FileChangeVO> reject(
            @AuthUserId Long userId,
            @PathVariable("repoId") Long repoId,
            @PathVariable("changeId") Long changeId) {
        return Result.success(fileChangeService.reject(userId, repoId, changeId));
    }

    /** 审批通过某会话下全部 PROPOSED 变更（逐条写盘）。ack=true 时跳过 BLOCK 拦截。 */
    @PostMapping("/apply-all")
    public Result<List<FileChangeVO>> applyAll(
            @AuthUserId Long userId,
            @PathVariable("repoId") Long repoId,
            @RequestParam(value = "sessionId", required = false) Long sessionId,
            @RequestParam(value = "ack", required = false) boolean ack) {
        return Result.success(fileChangeService.applyAll(userId, repoId, sessionId, ack));
    }

    /** 拒绝某会话下全部 PROPOSED 变更（不写盘）。 */
    @PostMapping("/reject-all")
    public Result<List<FileChangeVO>> rejectAll(
            @AuthUserId Long userId,
            @PathVariable("repoId") Long repoId,
            @RequestParam(value = "sessionId", required = false) Long sessionId) {
        return Result.success(fileChangeService.rejectAll(userId, repoId, sessionId));
    }

    /** 回滚一条已 APPLIED 的变更：把旧内容写回文件并追加回滚记录。 */
    @PostMapping("/{changeId}/revert")
    public Result<FileChangeVO> revert(
            @AuthUserId Long userId,
            @PathVariable("repoId") Long repoId,
            @PathVariable("changeId") Long changeId) {
        return Result.success(fileChangeService.revert(userId, repoId, changeId));
    }

    /** 列出某会话下所有变更的风险标志（供前端展示破坏性操作警告）。 */
    @GetMapping("/risk")
    public Result<List<ChangeRiskVO>> listRisk(
            @AuthUserId Long userId,
            @PathVariable("repoId") Long repoId,
            @RequestParam Long sessionId) {
        return Result.success(changeRiskService.listBySession(userId, repoId, sessionId));
    }

    /** 用户勾选确认某条变更的所有风险标志。 */
    @PostMapping("/{changeId}/acknowledge-risk")
    public Result<Void> acknowledgeRisk(
            @AuthUserId Long userId,
            @PathVariable("repoId") Long repoId,
            @PathVariable("changeId") Long changeId) {
        changeRiskService.acknowledge(userId, repoId, changeId);
        return Result.success(null);
    }
}
