package com.repolens.controller;

import com.repolens.common.result.Result;
import com.repolens.domain.dto.FileWriteRequest;
import com.repolens.domain.vo.FileWriteResultVO;
import com.repolens.security.AuthUserId;
import com.repolens.service.RepoFileWriteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class FileWriteController {

    private final RepoFileWriteService repoFileWriteService;

    @PutMapping("/api/files/content")
    public Result<FileWriteResultVO> writeFile(@AuthUserId Long userId,
                                               @Valid @RequestBody FileWriteRequest request) {
        return Result.success(repoFileWriteService.writeFile(userId, request));
    }
}
