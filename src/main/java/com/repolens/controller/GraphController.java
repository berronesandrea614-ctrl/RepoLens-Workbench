package com.repolens.controller;

import com.repolens.common.result.Result;
import com.repolens.domain.vo.CodeGraphVO;
import com.repolens.security.AuthUserId;
import com.repolens.service.CodeGraphService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/repos")
public class GraphController {

    private final CodeGraphService codeGraphService;

    @GetMapping("/{repoId}/graph")
    public Result<CodeGraphVO> graph(@AuthUserId Long userId,
                                     @PathVariable("repoId") Long repoId,
                                     @RequestParam("rootSymbolId") Long rootSymbolId,
                                     @RequestParam(value = "direction", defaultValue = "callees") String direction,
                                     @RequestParam(value = "depth", defaultValue = "2") int depth,
                                     @RequestParam(value = "minConfidence", defaultValue = "0") double minConfidence) {
        return Result.success(codeGraphService.buildGraph(userId, repoId, rootSymbolId, direction, depth, minConfidence));
    }
}
