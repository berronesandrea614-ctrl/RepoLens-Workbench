package com.repolens.controller;

import com.repolens.common.result.Result;
import com.repolens.domain.vo.CodeGraphVO;
import com.repolens.domain.vo.TimelineVO;
import com.repolens.security.AuthUserId;
import com.repolens.service.ArchTimelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/repos/{repoId}/timeline")
public class ArchTimelineController {

    private final ArchTimelineService archTimelineService;

    @GetMapping
    public Result<TimelineVO> timeline(@AuthUserId Long userId,
                                       @PathVariable("repoId") Long repoId) {
        return Result.success(archTimelineService.getTimeline(userId, repoId));
    }

    @GetMapping("/{frameIndex}/graph")
    public Result<CodeGraphVO> frameGraph(@AuthUserId Long userId,
                                          @PathVariable("repoId") Long repoId,
                                          @PathVariable("frameIndex") int frameIndex) {
        return Result.success(archTimelineService.getFrameGraph(userId, repoId, frameIndex));
    }
}
