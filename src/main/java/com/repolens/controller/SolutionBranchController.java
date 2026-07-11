package com.repolens.controller;

import com.repolens.common.result.Result;
import com.repolens.domain.vo.BranchGraphVO;
import com.repolens.domain.vo.FileChangeVO;
import com.repolens.security.AuthUserId;
import com.repolens.service.SolutionBranchService;
import lombok.Data;
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
 * K方案分支图 REST 接口。
 *
 * <p>P1 fanout 为同步阻塞（等全部变体完成再返回），P2 再改 SSE 骨架推送。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/repos/{repoId}/branches")
public class SolutionBranchController {

    private final SolutionBranchService solutionBranchService;

    /**
     * 并发产 N 条候选方案变体，同步等待全部完成后返回分支图。
     *
     * <p>POST /api/repos/{repoId}/branches/fanout
     */
    @PostMapping("/fanout")
    public Result<BranchGraphVO> fanout(
            @AuthUserId Long userId,
            @PathVariable("repoId") Long repoId,
            @RequestBody FanoutReq req) {
        BranchGraphVO vo = solutionBranchService.fanout(
                userId, repoId, req.getSessionId(), req.getQuestion(),
                req.getVariantCount(), req.getStrategies());
        return Result.success(vo);
    }

    /**
     * 查询该会话下的分支图（全部候选方案节点）。
     *
     * <p>GET /api/repos/{repoId}/branches?sessionId=xxx
     */
    @GetMapping
    public Result<BranchGraphVO> graph(
            @AuthUserId Long userId,
            @PathVariable("repoId") Long repoId,
            @RequestParam("sessionId") Long sessionId) {
        return Result.success(solutionBranchService.getBranchGraph(userId, repoId, sessionId));
    }

    /**
     * 选中一条方案分支：SELECTED + 其余 DISCARDED + apply 选中方案的文件变更。
     *
     * <p>POST /api/repos/{repoId}/branches/{branchId}/select
     */
    @PostMapping("/{branchId}/select")
    public Result<List<FileChangeVO>> select(
            @AuthUserId Long userId,
            @PathVariable("repoId") Long repoId,
            @PathVariable("branchId") String branchId,
            @RequestBody SelectReq req) {
        List<FileChangeVO> applied = solutionBranchService.select(
                userId, repoId, req.getSessionId(), branchId, req.isAck());
        return Result.success(applied);
    }

    // ========================= Request DTOs ========================= //

    /** fanout 请求体。 */
    @Data
    public static class FanoutReq {
        private Long sessionId;
        private String question;
        /** 变体数量，[2,4]，超出范围自动 clamp。 */
        private int variantCount = 3;
        /** 各变体策略提示（可空，使用默认策略列表）。 */
        private List<String> strategies;
    }

    /** select 请求体。 */
    @Data
    public static class SelectReq {
        private Long sessionId;
        /** 用户已确认风险标志。 */
        private boolean ack;
    }
}
