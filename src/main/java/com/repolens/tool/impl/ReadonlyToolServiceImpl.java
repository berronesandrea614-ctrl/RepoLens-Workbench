package com.repolens.tool.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.CodeDependencyEntity;
import com.repolens.domain.entity.CodeSymbolEntity;
import com.repolens.domain.entity.RepoEntity;
import com.repolens.domain.entity.ToolCallLogEntity;
import com.repolens.domain.enums.SymbolType;
import com.repolens.domain.enums.ToolName;
import com.repolens.domain.vo.ApiVO;
import com.repolens.domain.vo.CodeReferenceVO;
import com.repolens.domain.vo.FileContentVO;
import com.repolens.domain.vo.ImpactAnalysisVO;
import com.repolens.domain.vo.ImpactItemVO;
import com.repolens.domain.vo.RagSearchResultVO;
import com.repolens.domain.vo.SymbolVO;
import com.repolens.domain.vo.ToolMethodCalleeVO;
import com.repolens.domain.vo.ToolMethodCallerVO;
import com.repolens.domain.entity.CodeFileEntity;
import com.repolens.mapper.CodeDependencyMapper;
import com.repolens.mapper.CodeFileMapper;
import com.repolens.mapper.CodeSymbolMapper;
import com.repolens.mapper.RepoMapper;
import com.repolens.mapper.ToolCallLogMapper;
import com.repolens.security.PermissionService;
import com.repolens.service.RagRetrievalService;
import com.repolens.service.support.RepoWorkspaceResolver;
import com.repolens.tool.ReadonlyToolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * RepoLens 只读工具服务实现。
 * 设计意图：
 * 1) 对外提供可审计的只读代码工具；
 * 2) 所有工具调用统一走权限校验与 tool_call_log；
 * 3) 作为后续 Agent 调用的稳定基础能力层。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReadonlyToolServiceImpl implements ReadonlyToolService {

    private static final int DEFAULT_TOP_K = 8;
    private static final int MAX_TOP_K = 20;
    private static final int DEFAULT_FILE_PREVIEW_LINES = 200;
    private static final int MAX_LOG_JSON_LENGTH = 4000;
    private static final int MAX_ERROR_MSG_LENGTH = 800;

    private final PermissionService permissionService;
    private final RepoMapper repoMapper;
    private final CodeSymbolMapper codeSymbolMapper;
    private final CodeDependencyMapper codeDependencyMapper;
    private final CodeFileMapper codeFileMapper;
    private final ToolCallLogMapper toolCallLogMapper;
    private final RagRetrievalService ragRetrievalService;
    private final ObjectMapper objectMapper;
    private final RepoWorkspaceResolver repoWorkspaceResolver;

    /**
     * 工具：searchCodeChunks
     * 说明：直接复用 RAG 检索链路，只返回引用信息，不在工具层做 LLM 生成。
     */
    @Override
    public List<CodeReferenceVO> searchCodeChunks(Long userId, Long repoId, String query, Integer topK) {
        Map<String, Object> input = inputOf("query", query, "topK", topK);
        return executeWithLog(userId, repoId, ToolName.SEARCH_CODE_CHUNKS, input, () -> {
            ensureRepoPermission(userId, repoId);
            RagSearchResultVO ragResult = ragRetrievalService.retrieve(repoId, userId, query, normalizeTopK(topK));
            return ragResult.getResults().stream()
                    .map(chunk -> CodeReferenceVO.builder()
                            .filePath(chunk.getFilePath())
                            .className(null)
                            .methodName(null)
                            .startLine(chunk.getStartLine())
                            .endLine(chunk.getEndLine())
                            .build())
                    .toList();
        });
    }

    /**
     * 工具：getFileContent
     * 说明：基于本地仓库读取源码片段，路径必须在 repo 根目录内，防止路径穿越。
     */
    /**
     * 工具：getFileContent。
     * 这里读取的是仓库本地快照，不是任意服务器文件；最终路径必须落在当前 repo 目录内。
     */
    @Override
    public FileContentVO getFileContent(Long userId, Long repoId, String filePath, Integer startLine, Integer endLine) {
        Map<String, Object> input = inputOf("filePath", filePath, "startLine", startLine, "endLine", endLine);
        return executeWithLog(userId, repoId, ToolName.GET_FILE_CONTENT, input, () -> {
            ensureRepoPermission(userId, repoId);
            if (!StringUtils.hasText(filePath)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "filePath is required");
            }

            RepoEntity repo = loadRepoOrThrow(repoId);
            Path repoDirectory = resolveRepoDirectory(repo);
            String effectivePath = filePath;
            Path targetFile = resolveSafeFilePath(repoDirectory, effectivePath);
            if (!Files.exists(targetFile) || !Files.isRegularFile(targetFile)) {
                // 裸名兜底：agent 常只给类文件名（如 "UserController.java"）而非全相对路径。
                // 若直连路径不存在，用 code_file 表按 basename 唯一匹配解析出全路径再读，
                // 免得 agent 因 "File not found" 白白浪费一次迭代。仍走 resolveSafeFilePath 保安全。
                String resolved = resolveByBaseName(repoId, effectivePath);
                if (resolved != null) {
                    Path retry = resolveSafeFilePath(repoDirectory, resolved);
                    if (Files.exists(retry) && Files.isRegularFile(retry)) {
                        targetFile = retry;
                        effectivePath = resolved;
                    }
                }
                if (!Files.exists(targetFile) || !Files.isRegularFile(targetFile)) {
                    throw new BizException(ErrorCode.NOT_FOUND, "File not found in local repository");
                }
            }

            try {
                List<String> lines = Files.readAllLines(targetFile, StandardCharsets.UTF_8);
                LineRange range = normalizeLineRange(lines.size(), startLine, endLine);
                String content = joinLines(lines, range.startLine(), range.endLine());
                return FileContentVO.builder()
                        .repoId(repoId)
                        .filePath(effectivePath)
                        .startLine(range.startLine())
                        .endLine(range.endLine())
                        .content(content)
                        .build();
            } catch (Exception ex) {
                throw new BizException(ErrorCode.SYSTEM_ERROR, "Read file content failed: " + trimError(ex.getMessage()));
            }
        });
    }

    /**
     * 工具：findApiByPath
     * 说明：优先精确匹配 API 路径，若没有命中再做模糊匹配。
     */
    @Override
    public List<ApiVO> findApiByPath(Long userId, Long repoId, String apiPath) {
        Map<String, Object> input = inputOf("apiPath", apiPath);
        return executeWithLog(userId, repoId, ToolName.FIND_API_BY_PATH, input, () -> {
            ensureRepoPermission(userId, repoId);
            if (!StringUtils.hasText(apiPath)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "apiPath is required");
            }

            List<CodeSymbolEntity> exact = codeSymbolMapper.selectList(Wrappers.<CodeSymbolEntity>lambdaQuery()
                    .eq(CodeSymbolEntity::getRepoId, repoId)
                    .eq(CodeSymbolEntity::getSymbolType, SymbolType.API)
                    .eq(CodeSymbolEntity::getApiPath, apiPath)
                    .orderByAsc(CodeSymbolEntity::getClassName)
                    .orderByAsc(CodeSymbolEntity::getMethodName));
            if (!exact.isEmpty()) {
                return exact.stream().map(this::toApiVO).toList();
            }

            List<CodeSymbolEntity> fuzzy = codeSymbolMapper.selectList(Wrappers.<CodeSymbolEntity>lambdaQuery()
                    .eq(CodeSymbolEntity::getRepoId, repoId)
                    .eq(CodeSymbolEntity::getSymbolType, SymbolType.API)
                    .like(CodeSymbolEntity::getApiPath, apiPath)
                    .orderByAsc(CodeSymbolEntity::getClassName)
                    .orderByAsc(CodeSymbolEntity::getMethodName)
                    .last("LIMIT 50"));
            return fuzzy.stream().map(this::toApiVO).toList();
        });
    }

    @Override
    public List<SymbolVO> findSymbolByName(Long userId, Long repoId, String symbolName) {
        Map<String, Object> input = inputOf("symbolName", symbolName);
        return executeWithLog(userId, repoId, ToolName.FIND_SYMBOL_BY_NAME, input, () -> {
            ensureRepoPermission(userId, repoId);
            if (!StringUtils.hasText(symbolName)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "symbolName is required");
            }

            return codeSymbolMapper.selectList(Wrappers.<CodeSymbolEntity>lambdaQuery()
                            .eq(CodeSymbolEntity::getRepoId, repoId)
                            .and(w -> w.like(CodeSymbolEntity::getClassName, symbolName)
                                    .or()
                                    .like(CodeSymbolEntity::getMethodName, symbolName))
                            .orderByAsc(CodeSymbolEntity::getFileId)
                            .orderByAsc(CodeSymbolEntity::getStartLine)
                            .last("LIMIT 100"))
                    .stream()
                    .map(symbol -> SymbolVO.builder()
                            .id(symbol.getId())
                            .fileId(symbol.getFileId())
                            .symbolType(symbol.getSymbolType())
                            .className(symbol.getClassName())
                            .methodName(symbol.getMethodName())
                            .signature(symbol.getSignature())
                            .startLine(symbol.getStartLine())
                            .endLine(symbol.getEndLine())
                            .summary(symbol.getSummary())
                            .build())
                    .toList();
        });
    }

    /**
     * 工具：findMethodCallers
     * 说明：通过 dependency.target_symbol_name 反查调用方 method 符号。
     */
    @Override
    public List<ToolMethodCallerVO> findMethodCallers(Long userId, Long repoId, String symbolName) {
        Map<String, Object> input = inputOf("symbolName", symbolName);
        return executeWithLog(userId, repoId, ToolName.FIND_METHOD_CALLERS, input, () -> {
            ensureRepoPermission(userId, repoId);
            if (!StringUtils.hasText(symbolName)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "symbolName is required");
            }

            List<Long> sourceIds = codeDependencyMapper.selectList(Wrappers.<CodeDependencyEntity>lambdaQuery()
                            .eq(CodeDependencyEntity::getRepoId, repoId)
                            .like(CodeDependencyEntity::getTargetSymbolName, symbolName))
                    .stream()
                    .map(CodeDependencyEntity::getSourceSymbolId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            if (sourceIds.isEmpty()) {
                return List.of();
            }

            Map<Long, CodeSymbolEntity> sourceMap = codeSymbolMapper.selectBatchIds(sourceIds).stream()
                    .filter(Objects::nonNull)
                    .collect(LinkedHashMap::new, (map, symbol) -> map.put(symbol.getId(), symbol), Map::putAll);
            return sourceIds.stream()
                    .map(sourceMap::get)
                    .filter(Objects::nonNull)
                    .map(symbol -> ToolMethodCallerVO.builder()
                            .sourceSymbolId(symbol.getId())
                            .className(symbol.getClassName())
                            .methodName(symbol.getMethodName())
                            .signature(symbol.getSignature())
                            .startLine(symbol.getStartLine())
                            .endLine(symbol.getEndLine())
                            .build())
                    .toList();
        });
    }

    /**
     * 工具：findMethodCallees
     * 说明：先定位 source method，再查 dependency 取被调用方法名。
     */
    @Override
    public List<ToolMethodCalleeVO> findMethodCallees(Long userId, Long repoId, String symbolName) {
        Map<String, Object> input = inputOf("symbolName", symbolName);
        return executeWithLog(userId, repoId, ToolName.FIND_METHOD_CALLEES, input, () -> {
            ensureRepoPermission(userId, repoId);
            if (!StringUtils.hasText(symbolName)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "symbolName is required");
            }

            List<Long> symbolIds = codeSymbolMapper.selectList(Wrappers.<CodeSymbolEntity>lambdaQuery()
                            .eq(CodeSymbolEntity::getRepoId, repoId)
                            .and(w -> w.like(CodeSymbolEntity::getMethodName, symbolName)
                                    .or()
                                    .like(CodeSymbolEntity::getClassName, symbolName)))
                    .stream()
                    .map(CodeSymbolEntity::getId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            if (symbolIds.isEmpty()) {
                return List.of();
            }

            return codeDependencyMapper.selectList(Wrappers.<CodeDependencyEntity>lambdaQuery()
                            .eq(CodeDependencyEntity::getRepoId, repoId)
                            .in(CodeDependencyEntity::getSourceSymbolId, symbolIds))
                    .stream()
                    .map(CodeDependencyEntity::getTargetSymbolName)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .map(target -> ToolMethodCalleeVO.builder()
                            .targetSymbolName(target)
                            .build())
                    .toList();
        });
    }

    /**
     * 工具：analyzeImpact
     * 说明：MVP 静态近似影响分析，不做编译级调用图推断。
     */
    @Override
    public ImpactAnalysisVO analyzeImpact(Long userId, Long repoId, String className, String methodName) {
        Map<String, Object> input = inputOf("className", className, "methodName", methodName);
        return executeWithLog(userId, repoId, ToolName.ANALYZE_IMPACT, input, () -> {
            ensureRepoPermission(userId, repoId);
            if (!StringUtils.hasText(methodName)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "methodName is required");
            }

            List<CodeDependencyEntity> dependencies = codeDependencyMapper.selectList(Wrappers.<CodeDependencyEntity>lambdaQuery()
                    .eq(CodeDependencyEntity::getRepoId, repoId)
                    .like(CodeDependencyEntity::getTargetSymbolName, methodName)
                    .orderByDesc(CodeDependencyEntity::getConfidence)
                    .orderByAsc(CodeDependencyEntity::getId)
                    .last("LIMIT 200"));
            if (dependencies.isEmpty()) {
                return ImpactAnalysisVO.builder()
                        .className(className)
                        .methodName(methodName)
                        .affectedMethodCount(0)
                        .affectedApiCount(0)
                        .impacts(List.of())
                        .build();
            }

            Set<Long> sourceSymbolIds = dependencies.stream()
                    .map(CodeDependencyEntity::getSourceSymbolId)
                    .filter(Objects::nonNull)
                    .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
            Map<Long, CodeSymbolEntity> sourceSymbolMap = codeSymbolMapper.selectBatchIds(new ArrayList<>(sourceSymbolIds))
                    .stream()
                    .filter(Objects::nonNull)
                    .collect(LinkedHashMap::new, (map, symbol) -> map.put(symbol.getId(), symbol), Map::putAll);

            List<CodeSymbolEntity> relatedApiSymbols = codeSymbolMapper.selectList(Wrappers.<CodeSymbolEntity>lambdaQuery()
                    .eq(CodeSymbolEntity::getRepoId, repoId)
                    .eq(CodeSymbolEntity::getSymbolType, SymbolType.API)
                    .last("LIMIT 500"));

            Map<String, List<CodeSymbolEntity>> apiByMethodKey = new HashMap<>();
            for (CodeSymbolEntity apiSymbol : relatedApiSymbols) {
                String key = methodKey(apiSymbol.getClassName(), apiSymbol.getMethodName());
                apiByMethodKey.computeIfAbsent(key, k -> new ArrayList<>()).add(apiSymbol);
            }

            List<ImpactItemVO> impacts = new ArrayList<>();
            for (CodeDependencyEntity dependency : dependencies) {
                CodeSymbolEntity source = sourceSymbolMap.get(dependency.getSourceSymbolId());
                if (source == null) {
                    continue;
                }
                if (StringUtils.hasText(className)
                        && !containsIgnoreCase(source.getClassName(), className)) {
                    continue;
                }
                float baseConfidence = normalizeConfidence(dependency.getConfidence());
                impacts.add(ImpactItemVO.builder()
                        .className(source.getClassName())
                        .methodName(source.getMethodName())
                        .apiPath(null)
                        .httpMethod(null)
                        .confidence(baseConfidence)
                        .reason("Caller matched by static dependency target_symbol_name")
                        .build());

                String sourceMethodKey = methodKey(source.getClassName(), source.getMethodName());
                List<CodeSymbolEntity> apis = apiByMethodKey.getOrDefault(sourceMethodKey, List.of());
                for (CodeSymbolEntity api : apis) {
                    impacts.add(ImpactItemVO.builder()
                            .className(source.getClassName())
                            .methodName(source.getMethodName())
                            .apiPath(api.getApiPath())
                            .httpMethod(api.getHttpMethod())
                            .confidence(Math.min(1.0f, baseConfidence + 0.1f))
                            .reason("Potential API impact via caller method")
                            .build());
                }
            }

            Map<String, ImpactItemVO> unique = new LinkedHashMap<>();
            for (ImpactItemVO item : impacts) {
                unique.put(impactKey(item), item);
            }
            List<ImpactItemVO> deduplicated = unique.values().stream()
                    .sorted(Comparator.comparing((ImpactItemVO item) -> item.getConfidence() == null ? 0.0f : item.getConfidence()).reversed())
                    .toList();

            int apiCount = (int) deduplicated.stream().filter(item -> StringUtils.hasText(item.getApiPath())).count();
            int methodCount = deduplicated.size() - apiCount;
            return ImpactAnalysisVO.builder()
                    .className(className)
                    .methodName(methodName)
                    .affectedMethodCount(methodCount)
                    .affectedApiCount(apiCount)
                    .impacts(deduplicated)
                    .build();
        });
    }

    private ApiVO toApiVO(CodeSymbolEntity symbol) {
        return ApiVO.builder()
                .id(symbol.getId())
                .apiPath(symbol.getApiPath())
                .httpMethod(symbol.getHttpMethod())
                .className(symbol.getClassName())
                .methodName(symbol.getMethodName())
                .startLine(symbol.getStartLine())
                .endLine(symbol.getEndLine())
                .build();
    }

    /**
     * 工具调用统一复用 repo 权限边界，避免绕过 Repo/RAG/Chat 的授权模型。
     */
    private void ensureRepoPermission(Long userId, Long repoId) {
        if (!permissionService.checkRepoPermission(userId, repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "No repo permission");
        }
    }

    private RepoEntity loadRepoOrThrow(Long repoId) {
        RepoEntity repo = repoMapper.selectById(repoId);
        if (repo == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "Repo not found");
        }
        return repo;
    }

    private Path resolveRepoDirectory(RepoEntity repo) {
        return repoWorkspaceResolver.resolveReadDirectory(repo);
    }

    private Path resolveSafeFilePath(Path repoDirectory, String relativePath) {
        return repoWorkspaceResolver.resolveSafeFilePath(repoDirectory, relativePath);
    }

    /**
     * 裸文件名 → 全相对路径兜底：仅当传入是不含 '/' 的纯文件名时，在 code_file 表里按
     * basename 匹配；唯一命中才返回其全相对路径，多义或无命中返回 null（不猜）。
     */
    private String resolveByBaseName(Long repoId, String filePath) {
        if (filePath == null || filePath.contains("/") || filePath.contains("\\")) {
            return null;
        }
        String baseName = filePath.trim();
        List<CodeFileEntity> files = codeFileMapper.selectList(
                Wrappers.<CodeFileEntity>lambdaQuery().eq(CodeFileEntity::getRepoId, repoId));
        List<String> matches = files.stream()
                .map(CodeFileEntity::getFilePath)
                .filter(StringUtils::hasText)
                .filter(p -> {
                    String norm = p.replace('\\', '/');
                    int slash = norm.lastIndexOf('/');
                    String base = slash >= 0 ? norm.substring(slash + 1) : norm;
                    return base.equalsIgnoreCase(baseName);
                })
                .distinct()
                .toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private LineRange normalizeLineRange(int totalLines, Integer startLine, Integer endLine) {
        if (totalLines <= 0) {
            return new LineRange(1, 1);
        }
        int start;
        int end;
        if (startLine == null && endLine == null) {
            start = 1;
            end = Math.min(DEFAULT_FILE_PREVIEW_LINES, totalLines);
        } else if (startLine != null && endLine == null) {
            // 先把 LLM 给的 startLine 夹到 [1, totalLines]，再用 long 计算窗口末端，
            // 避免 start 接近 Integer.MAX_VALUE 时 start+199 溢出成负数导致返回空串。
            start = clamp(startLine, 1, totalLines);
            end = (int) Math.min(totalLines, (long) start + DEFAULT_FILE_PREVIEW_LINES - 1);
        } else if (startLine == null) {
            end = clamp(endLine, 1, totalLines);
            start = (int) Math.max(1L, (long) end - DEFAULT_FILE_PREVIEW_LINES + 1);
        } else {
            start = clamp(startLine, 1, totalLines);
            end = clamp(endLine, 1, totalLines);
            end = Math.max(start, end);
        }
        return new LineRange(start, end);
    }

    /** 把 value 夹到 [min, max]，输入可能是 LLM 给的越界行号。 */
    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String joinLines(List<String> lines, int startLine, int endLine) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        int startIdx = Math.max(0, startLine - 1);
        int endIdx = Math.min(lines.size(), endLine);
        if (startIdx >= endIdx) {
            return "";
        }
        return String.join("\n", lines.subList(startIdx, endIdx));
    }

    private int normalizeTopK(Integer topK) {
        if (topK == null || topK <= 0) {
            return DEFAULT_TOP_K;
        }
        return Math.min(topK, MAX_TOP_K);
    }

    private Map<String, Object> inputOf(Object... kvPairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (kvPairs == null) {
            return map;
        }
        for (int i = 0; i + 1 < kvPairs.length; i += 2) {
            map.put(String.valueOf(kvPairs[i]), kvPairs[i + 1]);
        }
        return map;
    }

    /**
     * 审计日志写入：
     * - 成功和失败都记录；
     * - output_json 截断，避免写入大段源码；
     * - 不吞异常，业务错误继续向上抛出。
     */
    private <T> T executeWithLog(Long userId,
                                 Long repoId,
                                 ToolName toolName,
                                 Object input,
                                 ToolExecutor<T> executor) {
        long startTime = System.currentTimeMillis();
        boolean success = false;
        String output = null;
        String errorMsg = null;
        try {
            T result = executor.execute();
            success = true;
            output = toJson(result);
            return result;
        } catch (Exception ex) {
            errorMsg = trimError(ex.getMessage());
            log.warn("Readonly tool failed, toolName={}, repoId={}, userId={}, error={}",
                    toolName, repoId, userId, errorMsg);
            throw ex;
        } finally {
            try {
                ToolCallLogEntity logEntity = new ToolCallLogEntity();
                logEntity.setUserId(userId);
                logEntity.setRepoId(repoId);
                logEntity.setSessionId(null);
                logEntity.setToolName(toolName.name());
                logEntity.setInputJson(truncateJson(toJson(input)));
                logEntity.setOutputJson(truncateJson(output));
                logEntity.setSuccess(success);
                logEntity.setCostMs(System.currentTimeMillis() - startTime);
                logEntity.setErrorMsg(errorMsg);
                toolCallLogMapper.insert(logEntity);
            } catch (Exception logEx) {
                log.warn("Write tool_call_log failed, toolName={}, repoId={}, userId={}, reason={}",
                        toolName, repoId, userId, trimError(logEx.getMessage()));
            }
        }
    }

    private String toJson(Object object) {
        if (object == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException ex) {
            return String.valueOf(object);
        }
    }

    private String truncateJson(String text) {
        if (!StringUtils.hasText(text)) {
            return text;
        }
        if (text.length() <= MAX_LOG_JSON_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_LOG_JSON_LENGTH) + "...<truncated>";
    }

    private String trimError(String error) {
        if (!StringUtils.hasText(error)) {
            return null;
        }
        String trimmed = error.trim();
        if (trimmed.length() <= MAX_ERROR_MSG_LENGTH) {
            return trimmed;
        }
        return trimmed.substring(0, MAX_ERROR_MSG_LENGTH);
    }

    private String methodKey(String className, String methodName) {
        return (className == null ? "" : className) + "#" + (methodName == null ? "" : methodName);
    }

    private String impactKey(ImpactItemVO item) {
        return methodKey(item.getClassName(), item.getMethodName()) + "|"
                + (item.getApiPath() == null ? "" : item.getApiPath()) + "|"
                + (item.getHttpMethod() == null ? "" : item.getHttpMethod());
    }

    private float normalizeConfidence(BigDecimal confidence) {
        if (confidence == null) {
            return 0.5f;
        }
        BigDecimal bounded = confidence.max(BigDecimal.ZERO).min(BigDecimal.ONE);
        return bounded.setScale(2, RoundingMode.HALF_UP).floatValue();
    }

    private boolean containsIgnoreCase(String source, String pattern) {
        if (!StringUtils.hasText(source) || !StringUtils.hasText(pattern)) {
            return false;
        }
        return source.toLowerCase(Locale.ROOT).contains(pattern.toLowerCase(Locale.ROOT));
    }

    private record LineRange(int startLine, int endLine) {
    }

    @FunctionalInterface
    private interface ToolExecutor<T> {
        T execute();
    }
}
