package com.repolens.controller;

import com.repolens.common.result.Result;
import com.repolens.domain.vo.AdrVO;
import com.repolens.security.AuthUserId;
import com.repolens.service.AdrService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ADR REST endpoints.
 *
 * <pre>
 * POST /api/repos/{repoId}/adrs/generate           — crystallize from requirement, persist PROPOSED
 * GET  /api/repos/{repoId}/adrs                    — list ADRs (ordered by createdAt DESC)
 * GET  /api/repos/{repoId}/adrs/{adrId}            — get single ADR
 * POST /api/repos/{repoId}/adrs/{adrId}/accept     — accept: assign number + write docs/adr/NNNN.md
 * POST /api/repos/{repoId}/adrs/{adrId}/supersede  — explicitly mark adrId SUPERSEDED by supersedingAdrId
 * </pre>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/repos/{repoId}/adrs")
public class AdrController {

    private final AdrService adrService;

    @PostMapping("/generate")
    public Result<AdrVO> generate(
            @AuthUserId Long userId,
            @PathVariable Long repoId,
            @RequestBody GenerateReq req) {
        return Result.success(adrService.generateFromRequirement(userId, repoId, req.requirementId()));
    }

    @GetMapping
    public Result<List<AdrVO>> list(
            @AuthUserId Long userId,
            @PathVariable Long repoId) {
        return Result.success(adrService.list(userId, repoId));
    }

    @GetMapping("/{adrId}")
    public Result<AdrVO> get(
            @AuthUserId Long userId,
            @PathVariable Long repoId,
            @PathVariable Long adrId) {
        return Result.success(adrService.get(userId, repoId, adrId));
    }

    @PostMapping("/{adrId}/accept")
    public Result<AdrVO> accept(
            @AuthUserId Long userId,
            @PathVariable Long repoId,
            @PathVariable Long adrId) {
        return Result.success(adrService.accept(userId, repoId, adrId));
    }

    @PostMapping("/{adrId}/supersede")
    public Result<AdrVO> supersede(
            @AuthUserId Long userId,
            @PathVariable Long repoId,
            @PathVariable Long adrId,
            @RequestBody SupersedeReq req) {
        return Result.success(adrService.supersede(userId, repoId, adrId, req.supersedingAdrId()));
    }

    /** Request body for /generate. */
    public record GenerateReq(Long requirementId) {}

    /** Request body for /supersede. */
    public record SupersedeReq(Long supersedingAdrId) {}
}
