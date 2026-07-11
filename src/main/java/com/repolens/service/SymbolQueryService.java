package com.repolens.service;

import com.repolens.domain.vo.ApiVO;
import com.repolens.domain.vo.FileContentVO;
import com.repolens.domain.vo.SymbolVO;

import java.util.List;

public interface SymbolQueryService {

    List<SymbolVO> searchSymbols(Long userId, Long repoId, String symbolName);

    List<ApiVO> searchApis(Long userId, Long repoId, String apiPath);

    FileContentVO getFileContent(Long userId, Long repoId, String filePath, Integer startLine, Integer endLine);
}
