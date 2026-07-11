package com.repolens.controller;

import com.repolens.common.result.Result;
import com.repolens.domain.vo.ApiVO;
import com.repolens.domain.vo.SymbolVO;
import com.repolens.security.AuthUserId;
import com.repolens.service.SymbolQueryService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequiredArgsConstructor
public class SymbolController {

    private final SymbolQueryService symbolQueryService;

    @GetMapping("/api/symbols/search")
    public Result<List<SymbolVO>> searchSymbols(@AuthUserId Long userId,
                                                @RequestParam("repoId") @NotNull Long repoId,
                                                @RequestParam("symbolName") @NotBlank String symbolName) {
        return Result.success(symbolQueryService.searchSymbols(userId, repoId, symbolName));
    }

    @GetMapping("/api/apis/search")
    public Result<List<ApiVO>> searchApis(@AuthUserId Long userId,
                                          @RequestParam("repoId") @NotNull Long repoId,
                                          @RequestParam("apiPath") @NotBlank String apiPath) {
        return Result.success(symbolQueryService.searchApis(userId, repoId, apiPath));
    }
}
