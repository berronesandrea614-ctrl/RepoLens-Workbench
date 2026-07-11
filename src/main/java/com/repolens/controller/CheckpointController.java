package com.repolens.controller;

import com.repolens.common.result.Result;
import com.repolens.domain.entity.CheckpointEntity;
import com.repolens.service.support.CheckpointService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Checkpoint/Rewind HTTP 接口。
 * POST /api/checkpoint/create — 打 checkpoint
 * GET  /api/checkpoint/tree — 获取分支树
 * POST /api/checkpoint/{id}/rewind — 回滚到指定 checkpoint
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/checkpoint")
public class CheckpointController {

    private final CheckpointService checkpointService;

    @PostMapping("/create")
    public Result<Long> create(@RequestParam Long sessionId,
                               @RequestParam(required = false) Long parentId,
                               @RequestParam(required = false) Long highWaterMark,
                               @RequestParam(required = false) Long lastMessageId,
                               @RequestParam(required = false) String label) {
        Long id = checkpointService.createCheckpoint(sessionId, parentId,
                highWaterMark != null ? highWaterMark : 0L,
                lastMessageId, label);
        return Result.success(id);
    }

    @GetMapping("/tree")
    public Result<List<CheckpointEntity>> tree(@RequestParam Long sessionId) {
        return Result.success(checkpointService.getTree(sessionId));
    }

    @PostMapping("/{id}/rewind")
    public Result<Boolean> rewind(@PathVariable Long id,
                                  @RequestParam Long sessionId) {
        return Result.success(checkpointService.rewindTo(sessionId, id));
    }
}
