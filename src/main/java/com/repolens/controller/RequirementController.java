package com.repolens.controller;

import com.repolens.common.result.Result;
import com.repolens.domain.vo.CodeGraphVO;
import com.repolens.domain.vo.ReconciliationVO;
import com.repolens.domain.vo.RequirementInsightVO;
import com.repolens.domain.vo.RequirementVO;
import com.repolens.domain.vo.TraceForwardVO;
import com.repolens.domain.vo.TraceMapVO;
import com.repolens.domain.vo.TraceReverseVO;
import com.repolens.security.AuthUserId;
import com.repolens.service.ReconciliationService;
import com.repolens.service.RequirementInsightService;
import com.repolens.service.RequirementService;
import com.repolens.service.TraceabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 需求存储层 HTTP 接口：列出、取子图、手动沉淀、删除、意图可视化。均以 X-User-Id（默认 1）标识调用者。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/repos")
public class RequirementController {

    private final RequirementService requirementService;
    private final RequirementInsightService requirementInsightService;
    private final ReconciliationService reconciliationService;
    private final TraceabilityService traceabilityService;

    /** 列出该 (user, repo) 全部需求，最新在前。 */
    @GetMapping("/{repoId}/requirements")
    public Result<List<RequirementVO>> list(
            @AuthUserId Long userId,
            @PathVariable("repoId") Long repoId) {
        return Result.success(requirementService.list(userId, repoId));
    }

    /** 取某需求的调用子图（各种子子图并集合并）。 */
    @GetMapping("/{repoId}/requirements/{requirementId}/graph")
    public Result<CodeGraphVO> graph(
            @AuthUserId Long userId,
            @PathVariable("repoId") Long repoId,
            @PathVariable("requirementId") Long requirementId) {
        return Result.success(requirementService.requirementGraph(userId, repoId, requirementId));
    }

    /** 从某会话最新一轮问答手动沉淀一条需求。 */
    @PostMapping("/{repoId}/requirements/summarize")
    public Result<RequirementVO> summarize(
            @AuthUserId Long userId,
            @PathVariable("repoId") Long repoId,
            @RequestParam("sessionId") Long sessionId) {
        return Result.success(requirementService.summarize(userId, repoId, sessionId));
    }

    /** 删除单条需求及其关联位点。 */
    @DeleteMapping("/{repoId}/requirements/{requirementId}")
    public Result<Void> delete(
            @AuthUserId Long userId,
            @PathVariable("repoId") Long repoId,
            @PathVariable("requirementId") Long requirementId) {
        requirementService.delete(userId, repoId, requirementId);
        return Result.success(null);
    }

    /**
     * 取某需求的意图可视化聚合 VO（步骤/偏差/风险/flow/panorama）。
     * 支持三种降级形态：有计划有改动（完整）/ 无计划有改动（改动概览）/ 纯问答（只读依据）。
     */
    @GetMapping("/{repoId}/requirements/{requirementId}/insight")
    public Result<RequirementInsightVO> insight(
            @AuthUserId Long userId,
            @PathVariable("repoId") Long repoId,
            @PathVariable("requirementId") Long requirementId) {
        return Result.success(requirementInsightService.insight(userId, repoId, requirementId));
    }

    /**
     * 获取某需求的计划 vs 实际对账结果（惰性：有快照直接返回，否则计算后存快照）。
     * Feature B P1 — 全确定性，不依赖 LLM。
     */
    @GetMapping("/{repoId}/requirements/{requirementId}/reconciliation")
    public Result<ReconciliationVO> reconciliation(
            @AuthUserId Long userId,
            @PathVariable("repoId") Long repoId,
            @PathVariable("requirementId") Long requirementId) {
        return Result.success(reconciliationService.getOrCompute(userId, repoId, requirementId));
    }

    /**
     * 强制重算对账结果（apply/revert 改动后调用）。
     * Feature B P1。
     */
    @PostMapping("/{repoId}/requirements/{requirementId}/reconciliation/recompute")
    public Result<ReconciliationVO> reconciliationRecompute(
            @AuthUserId Long userId,
            @PathVariable("repoId") Long repoId,
            @PathVariable("requirementId") Long requirementId) {
        return Result.success(reconciliationService.recompute(userId, repoId, requirementId));
    }

    // ─── Feature C: Spec↔Implementation Bidirectional Traceability ──────────

    /**
     * 获取双向可追溯地图（惰性：有快照直接返回，否则按需计算）。
     * Feature C MVP.
     */
    @GetMapping("/{repoId}/traceability")
    public Result<TraceMapVO> traceabilityMap(
            @AuthUserId Long userId,
            @PathVariable("repoId") Long repoId) {
        return Result.success(traceabilityService.getOrComputeMap(userId, repoId));
    }

    /**
     * 强制重算双向可追溯地图（apply/revert 改动后调用）。
     * Feature C MVP.
     */
    @PostMapping("/{repoId}/traceability/recompute")
    public Result<TraceMapVO> traceabilityRecompute(
            @AuthUserId Long userId,
            @PathVariable("repoId") Long repoId) {
        return Result.success(traceabilityService.recompute(userId, repoId));
    }

    /**
     * 正向追溯：获取某需求的全部实现符号（DECLARED / RAG / CALLGRAPH links）。
     * Feature C MVP.
     */
    @GetMapping("/{repoId}/requirements/{requirementId}/trace")
    public Result<TraceForwardVO> forwardTrace(
            @AuthUserId Long userId,
            @PathVariable("repoId") Long repoId,
            @PathVariable("requirementId") Long requirementId) {
        return Result.success(traceabilityService.forwardTrace(userId, repoId, requirementId));
    }

    /**
     * 反向追溯：获取实现某符号的全部需求。
     * Feature C P1.
     */
    @GetMapping("/{repoId}/symbols/{symbolId}/trace")
    public Result<TraceReverseVO> reverseTrace(
            @AuthUserId Long userId,
            @PathVariable("repoId") Long repoId,
            @PathVariable("symbolId") Long symbolId) {
        return Result.success(traceabilityService.reverseTrace(userId, repoId, symbolId));
    }

    /**
     * 外部改动归纳入口（Claude Code 文件监听路径）。
     *
     * <p>Body: {@code { "changedFiles": ["src/Foo.java", ...], "realDir": "/abs/path", "sessionHint": "..." }}
     *
     * <p>读取变更文件内容 → 调 RequirementExtractor 归纳 → 落库（source="external"）→ 返回 requirementId。
     * 失败安全：文件读取失败、LLM 无归纳结果时返回 204 No Content。
     * 需要 JWT 认证（同其他需求接口）。
     */
    @PostMapping("/{repoId}/external-changes/summarize")
    public Result<Long> externalChangesSummarize(
            @AuthUserId Long userId,
            @PathVariable("repoId") Long repoId,
            @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> changedFiles = body.get("changedFiles") instanceof List<?> l
                ? (List<String>) l : List.of();
        String realDir     = body.get("realDir")     instanceof String s ? s : null;
        String sessionHint = body.get("sessionHint") instanceof String s ? s : null;

        Optional<RequirementVO> result = requirementService.summarizeExternal(
                userId, repoId, changedFiles, realDir, sessionHint);

        // Return the new requirementId, or null if nothing was summarized (frontend checks for null).
        return Result.success(result.map(RequirementVO::getId).orElse(null));
    }
}
