package com.repolens.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.common.util.HashUtils;
import com.repolens.domain.entity.CodeChunkEntity;
import com.repolens.domain.entity.CodeFileEntity;
import com.repolens.domain.entity.CodeSymbolEntity;
import com.repolens.domain.entity.RepoEntity;
import com.repolens.domain.enums.ChunkType;
import com.repolens.domain.enums.SymbolType;
import com.repolens.domain.enums.TaskStatus;
import com.repolens.domain.enums.VectorStatus;
import com.repolens.domain.vo.BuildChunkResultVO;
import com.repolens.mapper.CodeChunkMapper;
import com.repolens.mapper.CodeFileMapper;
import com.repolens.mapper.CodeSymbolMapper;
import com.repolens.mapper.RepoMapper;
import com.repolens.security.PermissionService;
import com.repolens.service.CodeChunkBuildService;
import com.repolens.service.impl.support.CodeChunkSegmenter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Chunk 构建服务实现，对应主链路第三阶段“构建检索粒度”。
 * 目标是把仓库文件切成更适合 RAG 的 chunk，并为每个 chunk 挂上：
 * 1. repo/file/symbol 归属关系；
 * 2. 行号范围；
 * 3. chunkType、language、contentHash；
 * 4. 初始向量状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeChunkBuildServiceImpl implements CodeChunkBuildService {

    private final RepoMapper repoMapper;
    private final CodeFileMapper codeFileMapper;
    private final CodeSymbolMapper codeSymbolMapper;
    private final CodeChunkMapper codeChunkMapper;
    private final PermissionService permissionService;
    private final PlatformTransactionManager txManager;

    @Value("${repolens.repo-storage-root:./workspace/repos}")
    private String repoStorageRoot;

    /**
     * 单个 chunk 的目标字符数。8000 字符 ≈ 2000+ tokens，远超 bge/nomic 的 512 输入窗口，
     * 因此改为可配置（默认 ~1500 字符 ≈ ~375 tokens）。
     */
    @Value("${repolens.indexing.chunk-size:1500}")
    private int chunkSize;

    /**
     * 相邻 chunk 之间的重叠字符数，避免语义在硬切边界处断裂（默认 200 字符）。
     */
    @Value("${repolens.indexing.chunk-overlap:200}")
    private int chunkOverlap;

    /**
     * 单文件字节上限：超过则视为 minified/vendored 大文件，直接跳过切块（默认 200KB）。
     * 这类文件（如 575KB 的 element.js）没有检索价值，反而会污染向量库并挤占召回。
     */
    @Value("${repolens.indexing.max-file-bytes:204800}")
    private long maxFileBytes;

    /**
     * 需要跳过的 vendor / 生成物目录片段。命中即认为不是可检索的业务源码。
     */
    private static final String[] VENDOR_PATH_SEGMENTS = {
            "node_modules/", "dist/", "build/", "target/", "vendor/", ".git/",
            "/static/", "/assets/", "webapp/resources/", "bower_components/", "third_party/"
    };

    /**
     * 需要跳过的文件后缀：minified / sourcemap / lock / 图片 / 字体等非源码文件。
     */
    private static final String[] SKIP_SUFFIXES = {
            ".min.js", ".min.css", ".map", ".lock",
            ".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico",
            ".woff", ".woff2", ".ttf", ".eot", ".otf"
    };

    /**
     * 判断某个文件是否值得进入切块流程。
     * 过滤 vendor/生成物路径、minified/二进制后缀以及超大文件，避免向量库被非源码污染。
     */
    boolean isChunkableSource(String relativePath, long sizeBytes) {
        if (!StringUtils.hasText(relativePath)) {
            return false;
        }
        if (maxFileBytes > 0 && sizeBytes > maxFileBytes) {
            return false;
        }
        String lower = relativePath.replace('\\', '/').toLowerCase();
        // 统一加前导斜杠后用 "/segment" 匹配，既能命中根目录也能命中任意子目录。
        String probe = "/" + lower;
        for (String segment : VENDOR_PATH_SEGMENTS) {
            String needle = segment.startsWith("/") ? segment : "/" + segment;
            if (probe.contains(needle)) {
                return false;
            }
        }
        for (String suffix : SKIP_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public BuildChunkResultVO buildChunks(Long repoId, Long userId) {
        RepoEntity repo = repoMapper.selectById(repoId);
        if (repo == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "Repo not found");
        }
        if (!permissionService.checkRepoPermission(userId, repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "No repo permission");
        }

        new TransactionTemplate(txManager).executeWithoutResult(status ->
            codeChunkMapper.delete(Wrappers.<CodeChunkEntity>lambdaQuery()
                    .eq(CodeChunkEntity::getRepoId, repoId)));

        List<CodeFileEntity> files = codeFileMapper.selectList(Wrappers.<CodeFileEntity>lambdaQuery()
                .eq(CodeFileEntity::getRepoId, repoId)
                .orderByAsc(CodeFileEntity::getFilePath));
        List<CodeSymbolEntity> symbols = codeSymbolMapper.selectList(Wrappers.<CodeSymbolEntity>lambdaQuery()
                .eq(CodeSymbolEntity::getRepoId, repoId)
                .orderByAsc(CodeSymbolEntity::getFileId)
                .orderByAsc(CodeSymbolEntity::getStartLine)
                .orderByAsc(CodeSymbolEntity::getId));

        Map<Long, List<CodeSymbolEntity>> symbolsByFile = symbols.stream()
                .filter(symbol -> symbol.getFileId() != null)
                .collect(Collectors.groupingBy(CodeSymbolEntity::getFileId));

        Path repoDirectory = resolveRepoDirectory(repo);
        BuildStats stats = new BuildStats();
        stats.totalFileCount = files.size();

        List<String> failedFiles = new ArrayList<>();
        int skippedFileCount = 0;
        for (CodeFileEntity file : files) {
            if (!isChunkableSource(file.getFilePath(), safeFileSize(repoDirectory, file.getFilePath()))) {
                skippedFileCount++;
                continue;
            }
            try {
                new TransactionTemplate(txManager).executeWithoutResult(status -> {
                    try {
                        processSingleFile(repoId, repoDirectory, file, symbolsByFile.get(file.getId()), stats);
                    } catch (Exception ex) {
                        throw new RuntimeException(ex.getMessage(), ex);
                    }
                });
            } catch (Exception ex) {
                stats.failedFileCount++;
                failedFiles.add(file.getFilePath());
                log.warn("Build chunk failed for file, repoId={}, filePath={}, reason={}",
                        repoId, file.getFilePath(), ex.getMessage());
            }
        }
        if (skippedFileCount > 0) {
            log.debug("Skipped non-source/vendor/oversized files during chunk build, repoId={}, skippedFileCount={}, totalFileCount={}",
                    repoId, skippedFileCount, stats.totalFileCount);
        }

        TaskStatus finalStatus = stats.totalFileCount > 0 && stats.failedFileCount >= stats.totalFileCount
                ? TaskStatus.FAILED
                : TaskStatus.SUCCESS;
        String errorMsg = null;
        if (!failedFiles.isEmpty()) {
            errorMsg = "Failed files: " + String.join(", ", failedFiles);
            if (errorMsg.length() > 2000) {
                errorMsg = errorMsg.substring(0, 2000);
            }
        }

        return BuildChunkResultVO.builder()
                .repoId(repoId)
                .totalFileCount(stats.totalFileCount)
                .javaFileCount(stats.javaFileCount)
                .configFileCount(stats.configFileCount)
                .classChunkCount(stats.classChunkCount)
                .methodChunkCount(stats.methodChunkCount)
                .apiChunkCount(stats.apiChunkCount)
                .configChunkCount(stats.configChunkCount)
                .docChunkCount(stats.docChunkCount)
                .totalChunkCount(stats.totalChunkCount)
                .failedFileCount(stats.failedFileCount)
                .status(finalStatus)
                .errorMsg(errorMsg)
                .build();
    }

    /**
     * 单文件 chunk 构建入口。
     * Java 文件按 symbol 切分，非 Java 文件按整文件切片，兼顾结构化信息和检索覆盖率。
     */
    private void processSingleFile(Long repoId,
                                   Path repoDirectory,
                                   CodeFileEntity file,
                                   List<CodeSymbolEntity> fileSymbols,
                                   BuildStats stats) throws Exception {
        Path filePath = resolveFilePath(repoDirectory, file.getFilePath());
        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            throw new BizException(ErrorCode.NOT_FOUND, "File not found in local repository: " + file.getFilePath());
        }

        List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
        String fileType = normalizeFileType(file.getFileType());
        if ("JAVA".equals(fileType)) {
            stats.javaFileCount++;
            buildJavaFileChunks(repoId, file, lines, fileSymbols, stats);
            return;
        }

        ChunkType nonJavaChunkType = resolveNonJavaChunkType(fileType);
        if (nonJavaChunkType == ChunkType.CONFIG) {
            stats.configFileCount++;
        }
        String language = resolveLanguage(fileType);
        buildWholeFileChunks(repoId, file, lines, nonJavaChunkType, language, null, stats);
    }

    /**
     * Java 文件优先按 METHOD / API / CLASS 三类符号切 chunk，
     * 让代码问答更容易返回可解释的局部证据。
     */
    private void buildJavaFileChunks(Long repoId,
                                     CodeFileEntity file,
                                     List<String> lines,
                                     List<CodeSymbolEntity> fileSymbols,
                                     BuildStats stats) {
        if (fileSymbols == null || fileSymbols.isEmpty()) {
            return;
        }

        List<CodeSymbolEntity> methodSymbols = filterSymbols(fileSymbols, SymbolType.METHOD);
        List<CodeSymbolEntity> apiSymbols = filterSymbols(fileSymbols, SymbolType.API);
        List<CodeSymbolEntity> classSymbols = filterSymbols(fileSymbols, SymbolType.CLASS);

        methodSymbols.forEach(symbol -> buildSymbolChunks(repoId, file, lines, symbol, ChunkType.METHOD, stats));
        apiSymbols.forEach(symbol -> buildSymbolChunks(repoId, file, lines, symbol, ChunkType.API, stats));
        classSymbols.forEach(symbol -> buildSymbolChunks(repoId, file, lines, symbol, ChunkType.CLASS, stats));
    }

    /**
     * 同类型符号按起始行排序，保证 chunk 构建顺序稳定，便于调试与重建。
     */
    private List<CodeSymbolEntity> filterSymbols(List<CodeSymbolEntity> symbols, SymbolType symbolType) {
        return symbols.stream()
                .filter(symbol -> symbolType == symbol.getSymbolType())
                .sorted(Comparator.comparing((CodeSymbolEntity s) -> safeLine(s.getStartLine()))
                        .thenComparing(CodeSymbolEntity::getId))
                .collect(Collectors.toList());
    }

    /**
     * 单个 symbol 可能很长，因此继续按最大字符数切成多个 slice。
     */
    private void buildSymbolChunks(Long repoId,
                                   CodeFileEntity file,
                                   List<String> lines,
                                   CodeSymbolEntity symbol,
                                   ChunkType chunkType,
                                   BuildStats stats) {
        int startLine = safeLine(symbol.getStartLine());
        int endLine = symbol.getEndLine() == null ? startLine : Math.max(startLine, symbol.getEndLine());
        List<CodeChunkSegmenter.ChunkSlice> slices =
                CodeChunkSegmenter.splitByMaxChars(lines, startLine, endLine, chunkSize, chunkOverlap);
        for (CodeChunkSegmenter.ChunkSlice slice : slices) {
            saveChunk(repoId, file, symbol.getId(), chunkType, "Java",
                    slice.getStartLine(), slice.getEndLine(), slice.getContent(), stats);
        }
    }

    /**
     * 非 Java 文件没有结构化 symbol，只能退化成“整文件按长度切片”。
     */
    private void buildWholeFileChunks(Long repoId,
                                      CodeFileEntity file,
                                      List<String> lines,
                                      ChunkType chunkType,
                                      String language,
                                      Long symbolId,
                                      BuildStats stats) {
        List<CodeChunkSegmenter.ChunkSlice> slices = CodeChunkSegmenter.splitByMaxChars(
                lines, 1, Math.max(1, lines.size()), chunkSize, chunkOverlap);
        if (slices.isEmpty()) {
            return;
        }
        for (CodeChunkSegmenter.ChunkSlice slice : slices) {
            saveChunk(repoId, file, symbolId, chunkType, language,
                    slice.getStartLine(), slice.getEndLine(), slice.getContent(), stats);
        }
    }

    /**
     * chunkId 由 repo/file/chunkType/lineRange/contentHash 共同计算，
     * 同一内容重复构建时 ID 稳定，内容变化时 ID 会一起变化。
     */
    private void saveChunk(Long repoId,
                           CodeFileEntity file,
                           Long symbolId,
                           ChunkType chunkType,
                           String language,
                           Integer startLine,
                           Integer endLine,
                           String content,
                           BuildStats stats) {
        if (!StringUtils.hasText(content)) {
            return;
        }
        String normalizedContent = content;
        String contentHash = HashUtils.sha256(normalizedContent);
        String rawChunkId = repoId + ":" + file.getFilePath() + ":" + chunkType.name() + ":"
                + startLine + ":" + endLine + ":" + contentHash;
        String chunkId = HashUtils.sha256(rawChunkId);

        CodeChunkEntity entity = new CodeChunkEntity();
        entity.setChunkId(chunkId);
        entity.setRepoId(repoId);
        entity.setFileId(file.getId());
        entity.setSymbolId(symbolId);
        entity.setChunkType(chunkType);
        entity.setLanguage(language);
        entity.setFilePath(file.getFilePath());
        entity.setStartLine(startLine);
        entity.setEndLine(endLine);
        entity.setContentHash(contentHash);
        entity.setContent(normalizedContent);
        entity.setSummary(null);
        entity.setVectorStatus(VectorStatus.PENDING);
        codeChunkMapper.insert(entity);

        stats.totalChunkCount++;
        countChunk(chunkType, stats);
    }

    /**
     * 统一维护各类 chunk 的计数，便于前端直接展示构建结果。
     */
    private void countChunk(ChunkType chunkType, BuildStats stats) {
        Consumer<BuildStats> counter = switch (chunkType) {
            case CLASS -> buildStats -> buildStats.classChunkCount++;
            case METHOD -> buildStats -> buildStats.methodChunkCount++;
            case API -> buildStats -> buildStats.apiChunkCount++;
            case CONFIG -> buildStats -> buildStats.configChunkCount++;
            case DOC -> buildStats -> buildStats.docChunkCount++;
            default -> buildStats -> { };
        };
        counter.accept(stats);
    }

    private ChunkType resolveNonJavaChunkType(String fileType) {
        return switch (fileType) {
            case "XML", "YAML", "PROPERTIES" -> ChunkType.CONFIG;
            case "MARKDOWN" -> ChunkType.DOC;
            case "SQL" -> ChunkType.SQL;
            default -> ChunkType.TEXT;
        };
    }

    private String resolveLanguage(String fileType) {
        return switch (fileType) {
            case "JAVA" -> "Java";
            case "XML" -> "XML";
            case "YAML" -> "YAML";
            case "PROPERTIES" -> "PROPERTIES";
            case "MARKDOWN" -> "MARKDOWN";
            case "SQL" -> "SQL";
            default -> "TEXT";
        };
    }

    private String normalizeFileType(String fileType) {
        if (!StringUtils.hasText(fileType)) {
            return "TEXT";
        }
        return fileType.trim().toUpperCase();
    }

    private int safeLine(Integer line) {
        return line == null || line <= 0 ? 1 : line;
    }

    private Path resolveRepoDirectory(RepoEntity repo) {
        String branchName = StringUtils.hasText(repo.getBranchName()) ? repo.getBranchName().trim() : "main";
        String sanitizedBranch = sanitizeBranchNameForPath(branchName);

        Path rootPath = Paths.get(repoStorageRoot).toAbsolutePath().normalize();
        Path repoPath = rootPath.resolve(String.valueOf(repo.getId()))
                .resolve(sanitizedBranch)
                .toAbsolutePath()
                .normalize();
        if (!repoPath.startsWith(rootPath)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Invalid repository local path");
        }
        if (!Files.exists(repoPath) || !Files.isDirectory(repoPath)) {
            throw new BizException(ErrorCode.NOT_FOUND, "Local repository not found, import repository first");
        }
        return repoPath;
    }

    /**
     * Chunk 构建阶段虽然只是读本地文件，但同样必须把最终路径限制在 repoDirectory 内，
     * 否则恶意 filePath 可能把切块读取带到仓库外。
     */
    private Path resolveFilePath(Path repoDirectory, String relativePath) {
        Path resolvedPath = repoDirectory.resolve(relativePath).toAbsolutePath().normalize();
        if (!resolvedPath.startsWith(repoDirectory)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "File path escapes repository root");
        }
        return resolvedPath;
    }

    /**
     * 尽力读取文件字节大小用于大文件过滤；无法解析（越界/不存在）时回落为 0，
     * 交由后续 processSingleFile 走既有的 NOT_FOUND 失败路径，不影响路径级过滤。
     */
    private long safeFileSize(Path repoDirectory, String relativePath) {
        try {
            Path filePath = resolveFilePath(repoDirectory, relativePath);
            if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
                return Files.size(filePath);
            }
        } catch (Exception ex) {
            log.debug("Unable to stat file size, filePath={}, reason={}", relativePath, ex.getMessage());
        }
        return 0L;
    }

    private String sanitizeBranchNameForPath(String branchName) {
        return branchName
                .replaceAll("[\\\\/:*?\"<>|]+", "_")
                .replaceAll("\\s+", "_");
    }

    private static class BuildStats {
        private int totalFileCount;
        private int javaFileCount;
        private int configFileCount;
        private int classChunkCount;
        private int methodChunkCount;
        private int apiChunkCount;
        private int configChunkCount;
        private int docChunkCount;
        private int totalChunkCount;
        private int failedFileCount;
    }
}
