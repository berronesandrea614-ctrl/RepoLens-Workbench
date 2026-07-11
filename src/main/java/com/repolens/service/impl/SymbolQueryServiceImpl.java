package com.repolens.service.impl;

import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.vo.ApiVO;
import com.repolens.domain.vo.FileContentVO;
import com.repolens.domain.vo.SymbolVO;
import com.repolens.service.SymbolQueryService;
import com.repolens.tool.ReadonlyToolService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SymbolQueryServiceImpl implements SymbolQueryService {

    private final ReadonlyToolService readonlyToolService;

    @Override
    public List<SymbolVO> searchSymbols(Long userId, Long repoId, String symbolName) {
        return readonlyToolService.findSymbolByName(userId, repoId, symbolName);
    }

    @Override
    public List<ApiVO> searchApis(Long userId, Long repoId, String apiPath) {
        return readonlyToolService.findApiByPath(userId, repoId, apiPath);
    }

    @Override
    public FileContentVO getFileContent(Long userId, Long repoId, String filePath, Integer startLine, Integer endLine) {
        // HTTP 层先做一层轻量拦截，真正的仓库目录边界仍由 ReadonlyToolService 再做 normalize 校验。
        if (filePath == null
                || filePath.contains("..")
                || filePath.startsWith("/")
                || filePath.startsWith("\\")
                || filePath.matches("^[a-zA-Z]:[\\\\/].+$")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Invalid filePath, path traversal is not allowed");
        }
        return readonlyToolService.getFileContent(userId, repoId, filePath, startLine, endLine);
    }
}
