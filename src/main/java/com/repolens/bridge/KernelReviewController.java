package com.repolens.bridge;

import com.repolens.common.result.Result;
import com.repolens.domain.entity.RepoEntity;
import com.repolens.kernel.realtime.ChangeReviewService;
import com.repolens.kernel.realtime.ChangeReviewService.PendingChange;
import com.repolens.kernel.realtime.ChangeReviewService.ReviewResult;
import com.repolens.mapper.RepoMapper;
import com.repolens.security.AuthUserId;
import com.repolens.service.support.RepoWorkspaceResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.List;

/**
 * 实时改动<b>逐处 accept/reject</b> 的 REST 接缝（bridge zone）——薄转发到内核
 * {@link ChangeReviewService}，把 god class 侧的 repoDir 解析（{@link RepoWorkspaceResolver}）喂进纯内核服务。
 *
 * <p>配合 {@code file_change} SSE 实时高亮：前端在编辑器逐个文件「接受/拒绝」时调这些端点。
 * accept=影子区该文件合并回真目录；reject=撤销影子区那处（真目录不动）。
 */
@RestController
@RequestMapping("/api/repos/{repoId}/agent/changes")
public class KernelReviewController {

    private final ChangeReviewService reviewService;
    private final RepoMapper repoMapper;
    private final RepoWorkspaceResolver repoWorkspaceResolver;

    public KernelReviewController(ChangeReviewService reviewService, RepoMapper repoMapper,
                                  RepoWorkspaceResolver repoWorkspaceResolver) {
        this.reviewService = reviewService;
        this.repoMapper = repoMapper;
        this.repoWorkspaceResolver = repoWorkspaceResolver;
    }

    /** 请求体：单文件评审。 */
    public record ReviewRequest(Long sessionId, String filePath) {
    }

    /** 请求体：整体评审。 */
    public record ReviewAllRequest(Long sessionId) {
    }

    /** 列出当前会话待审改动。 */
    @GetMapping
    public Result<List<PendingChange>> pending(@AuthUserId Long userId,
                                               @PathVariable Long repoId,
                                               @RequestParam Long sessionId) {
        return Result.success(reviewService.pending(repoId, sessionId));
    }

    /** 接受单个文件的改动（合并回真目录）。 */
    @PostMapping("/accept")
    public Result<ReviewResult> accept(@AuthUserId Long userId,
                                       @PathVariable Long repoId,
                                       @RequestBody ReviewRequest req) {
        return Result.success(reviewService.acceptFile(
                repoDir(repoId), repoId, req.sessionId(), req.filePath()));
    }

    /** 拒绝单个文件的改动（撤销影子区，真目录不动）。 */
    @PostMapping("/reject")
    public Result<ReviewResult> reject(@AuthUserId Long userId,
                                       @PathVariable Long repoId,
                                       @RequestBody ReviewRequest req) {
        return Result.success(reviewService.rejectFile(
                repoDir(repoId), repoId, req.sessionId(), req.filePath()));
    }

    /** 接受全部待审改动。 */
    @PostMapping("/accept-all")
    public Result<List<ReviewResult>> acceptAll(@AuthUserId Long userId,
                                                @PathVariable Long repoId,
                                                @RequestBody ReviewAllRequest req) {
        return Result.success(reviewService.acceptAll(repoDir(repoId), repoId, req.sessionId()));
    }

    /** 拒绝全部待审改动。 */
    @PostMapping("/reject-all")
    public Result<List<ReviewResult>> rejectAll(@AuthUserId Long userId,
                                                 @PathVariable Long repoId,
                                                 @RequestBody ReviewAllRequest req) {
        return Result.success(reviewService.rejectAll(repoDir(repoId), repoId, req.sessionId()));
    }

    private Path repoDir(Long repoId) {
        RepoEntity repo = repoMapper.selectById(repoId);
        if (repo == null) {
            throw new IllegalStateException("仓库不存在: " + repoId);
        }
        // 与 agent 写、前端读一致用「读目录」（file:// 本地仓库=真实项目目录），否则 accept 合并去向不一致。
        return repoWorkspaceResolver.resolveReadDirectory(repo);
    }
}
