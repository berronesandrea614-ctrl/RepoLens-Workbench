package com.repolens.controller;

import com.repolens.common.result.Result;
import com.repolens.domain.vo.ComprehensionDebtVO;
import com.repolens.domain.vo.FileExplanationVO;
import com.repolens.domain.vo.QuizQuestionVO;
import com.repolens.domain.vo.QuizResultVO;
import com.repolens.domain.vo.RepayPathVO;
import com.repolens.security.AuthUserId;
import com.repolens.service.ComprehensionDebtService;
import com.repolens.service.ComprehensionQuizService;
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
 * 理解债务仪表盘接口。
 *
 * <pre>
 * GET  /api/repos/{repoId}/comprehension-debt                          → 仪表盘（Top 列表 + 信号明细）
 * POST /api/repos/{repoId}/comprehension-debt/recompute                → 同步重算所有文件债务分
 * GET  /api/repos/{repoId}/comprehension-debt/{fileId}/repay           → 偿债路径（理由卡片 + 记忆）
 * GET  /api/repos/{repoId}/comprehension-debt/{fileId}/quiz            → 生成理解测验题
 * POST /api/repos/{repoId}/comprehension-debt/{fileId}/quiz/submit     → 提交答案 + 评分
 * POST /api/repos/{repoId}/changes/{changeId}/mark-reviewed            → 复核埋点（喂 S2）
 * </pre>
 *
 * 权限校验委托给 ComprehensionDebtService（与 FileChangeController 一致）。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/repos/{repoId}")
public class ComprehensionDebtController {

    private final ComprehensionDebtService comprehensionDebtService;
    private final ComprehensionQuizService comprehensionQuizService;

    // ------------------------------------------------------------------ //
    //  Dashboard                                                           //
    // ------------------------------------------------------------------ //

    /**
     * 获取理解债务仪表盘数据（懒触发：表空/stale 则先同步重算再返回）。
     *
     * @param minScore 最低分过滤阈值（默认 40，只显黄红）
     */
    @GetMapping("/comprehension-debt")
    public Result<ComprehensionDebtVO> getDashboard(
            @AuthUserId Long userId,
            @PathVariable("repoId") Long repoId,
            @RequestParam(value = "minScore", defaultValue = "40") int minScore) {
        return Result.success(comprehensionDebtService.getDashboard(repoId, userId, minScore));
    }

    /**
     * 同步触发仓库全量债务重算（适用于「重新计算债务」按钮）。
     * 响应：同步等待计算完成后返回，耗时可能数秒（大仓库）。
     */
    @PostMapping("/comprehension-debt/recompute")
    public Result<Void> recompute(
            @AuthUserId Long userId,
            @PathVariable("repoId") Long repoId) {
        comprehensionDebtService.recompute(repoId, userId);
        return Result.success(null);
    }

    // ------------------------------------------------------------------ //
    //  Repay path                                                          //
    // ------------------------------------------------------------------ //

    /**
     * 获取指定文件的偿债路径（理由卡片 + 长期记忆 + Claude 测验入口）。
     */
    @GetMapping("/comprehension-debt/{fileId}/repay")
    public Result<RepayPathVO> getRepayPath(
            @AuthUserId Long userId,
            @PathVariable("repoId") Long repoId,
            @PathVariable("fileId") Long fileId) {
        return Result.success(comprehensionDebtService.getRepayPath(repoId, fileId, userId));
    }

    // ------------------------------------------------------------------ //
    //  File explanation（帮助理解，替代考试式出题）                          //
    // ------------------------------------------------------------------ //

    /**
     * 生成文件讲解：LLM 讲清该文件在项目中的作用/职责 + 业务前因后果，
     * 并给出直接相关的文件/符号清单（含相对路径，供前端点击跳转）。
     * 结果按 {repoId,fileId} 缓存（点击才触发，非每次进页面调）；LLM 失败返回降级讲解。
     */
    @GetMapping("/comprehension-debt/{fileId}/explain")
    public Result<FileExplanationVO> explainFile(
            @AuthUserId Long userId,
            @PathVariable("repoId") Long repoId,
            @PathVariable("fileId") Long fileId) {
        return Result.success(comprehensionQuizService.explainFile(repoId, fileId, userId));
    }

    // ------------------------------------------------------------------ //
    //  Quiz（legacy，保留兼容）                                             //
    // ------------------------------------------------------------------ //

    /**
     * 生成文件理解测验题（3 道多选一，LLM 出题，失败降级到静态题）。
     * 题目不含正确答案（保存在 server 端 session 缓存）。
     */
    @GetMapping("/comprehension-debt/{fileId}/quiz")
    public Result<List<QuizQuestionVO>> generateQuiz(
            @AuthUserId Long userId,
            @PathVariable("repoId") Long repoId,
            @PathVariable("fileId") Long fileId) {
        return Result.success(comprehensionQuizService.generateQuiz(repoId, fileId, userId));
    }

    /**
     * 提交测验答案并评分。
     * 若得分 ≥ 65% 则自动调用 mark-reviewed(QUIZZED) → debt stale → 下次 GET 重算。
     */
    @PostMapping("/comprehension-debt/{fileId}/quiz/submit")
    public Result<QuizResultVO> submitQuiz(
            @AuthUserId Long userId,
            @PathVariable("repoId") Long repoId,
            @PathVariable("fileId") Long fileId,
            @RequestBody QuizSubmitRequest request) {
        return Result.success(comprehensionQuizService.submitQuiz(repoId, fileId, userId, request.getAnswers()));
    }

    // ------------------------------------------------------------------ //
    //  Mark-reviewed                                                       //
    // ------------------------------------------------------------------ //

    /**
     * 复核埋点：更新 file_change_log 复核字段，喂给 S2 信号。
     * reviewType: DIFF_VIEWED（看过diff）/ ACCEPTED（仅点接受）/ QUIZZED（过测验）。
     */
    @PostMapping("/changes/{changeId}/mark-reviewed")
    public Result<Void> markReviewed(
            @AuthUserId Long userId,
            @PathVariable("repoId") Long repoId,
            @PathVariable("changeId") Long changeId,
            @RequestBody MarkReviewedRequest request) {
        comprehensionDebtService.markReviewed(
                repoId, changeId,
                request.getReviewType(),
                request.getDwellMs(),
                request.getQuizScore(),
                userId);
        return Result.success(null);
    }

    // ------------------------------------------------------------------ //
    //  Request bodies                                                      //
    // ------------------------------------------------------------------ //

    /** 请求体：测验作答。 */
    @Data
    public static class QuizSubmitRequest {
        /**
         * 每题选中的 0-based 选项序号（顺序与 generateQuiz 返回的 questions 一致）。
         */
        private List<Integer> answers;
    }

    /** 请求体：复核埋点参数。 */
    @Data
    public static class MarkReviewedRequest {
        /** DIFF_VIEWED / ACCEPTED / QUIZZED */
        private String  reviewType;
        /** 停留时长（毫秒），可空 */
        private Long    dwellMs;
        /** 测验得分 0–100，QUIZZED 时必填 */
        private Integer quizScore;
    }
}
