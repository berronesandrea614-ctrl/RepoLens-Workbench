package com.repolens.tool;

import com.repolens.domain.vo.ApiVO;
import com.repolens.domain.vo.CodeReferenceVO;
import com.repolens.domain.vo.FileContentVO;
import com.repolens.domain.vo.ImpactAnalysisVO;
import com.repolens.domain.vo.SymbolVO;
import com.repolens.domain.vo.ToolMethodCalleeVO;
import com.repolens.domain.vo.ToolMethodCallerVO;

import java.util.List;

public interface ReadonlyToolService {

    List<CodeReferenceVO> searchCodeChunks(Long userId, Long repoId, String query, Integer topK);

    FileContentVO getFileContent(Long userId, Long repoId, String filePath, Integer startLine, Integer endLine);

    List<ApiVO> findApiByPath(Long userId, Long repoId, String apiPath);

    List<SymbolVO> findSymbolByName(Long userId, Long repoId, String symbolName);

    List<ToolMethodCallerVO> findMethodCallers(Long userId, Long repoId, String symbolName);

    List<ToolMethodCalleeVO> findMethodCallees(Long userId, Long repoId, String symbolName);

    ImpactAnalysisVO analyzeImpact(Long userId, Long repoId, String className, String methodName);
}
