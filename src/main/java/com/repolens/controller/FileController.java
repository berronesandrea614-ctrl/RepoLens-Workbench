package com.repolens.controller;

import com.repolens.common.result.Result;
import com.repolens.domain.vo.FileContentVO;
import com.repolens.security.AuthUserId;
import com.repolens.service.SymbolQueryService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
public class FileController {

    private final SymbolQueryService symbolQueryService;

    @GetMapping("/api/files/content")
    public Result<FileContentVO> getFileContent(@AuthUserId Long userId,
                                                @RequestParam("repoId") @NotNull Long repoId,
                                                @RequestParam("filePath") @NotBlank String filePath,
                                                @RequestParam("startLine") @NotNull Integer startLine,
                                                @RequestParam("endLine") @NotNull Integer endLine) {
        return Result.success(symbolQueryService.getFileContent(userId, repoId, filePath, startLine, endLine));
    }
}
