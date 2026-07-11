package com.repolens.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.domain.entity.CodeDependencyEntity;
import com.repolens.domain.entity.CodeFileEntity;
import com.repolens.domain.entity.CodeSymbolEntity;
import com.repolens.domain.entity.RepoEntity;
import com.repolens.domain.enums.SymbolType;
import com.repolens.mapper.CodeDependencyMapper;
import com.repolens.mapper.CodeFileMapper;
import com.repolens.mapper.CodeSymbolMapper;
import com.repolens.mapper.RepoMapper;
import com.repolens.service.support.RepoWorkspaceResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 多语言解析服务（Phase 1：TS/JS），通过进程外 tree-sitter sidecar 抽取符号与调用边，
 * 写入与 Java 解析共享的 {@code code_symbol} / {@code code_dependency} 表。
 *
 * <p><b>设计</b>：仿项目已有「shell out 到 ripgrep」模式，用 {@link ProcessBuilder} 调
 * {@code tools/code-parser-sidecar/index.js}，喂仓库目录 + 相对文件清单(stdin)，
 * 拿 {@code {symbols, dependencies}} JSON。加语言 = 加一个 grammar，不动 JVM、不改 Java 解析器。
 *
 * <p><b>与 Java 解析器的关系</b>：{@code JavaCodeParseServiceImpl} 会在解析开头删除该 repo
 * 的<em>全部</em> symbol/dependency，故本服务必须在 Java 解析<em>之后</em>调用，并且只删除/写入
 * {@code language ∈ (typescript, javascript)} 的行，不碰 Java 符号。
 *
 * <p><b>Fail-safe</b>：node 不在 / sidecar 依赖未装 / 解析异常 → 记 warn 并返回 0，
 * 绝不让索引主流程失败（TS 支持是增量能力，缺失不应阻断 Java 索引）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SidecarCodeParseService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 支持的源文件扩展名（小写，含点）。与 sidecar 的 grammar 注册表保持一致。 */
    private static final List<String> SUPPORTED_EXTS =
            List.of(".ts", ".mts", ".cts", ".tsx", ".js", ".mjs", ".cjs", ".jsx",
                    ".py", ".pyi", ".go", ".rs", ".cs", ".rb");

    /** sidecar 会写入的非 Java 语言值（用于清理旧数据时限定范围，绝不误删 Java 符号）。 */
    private static final List<String> SIDECAR_LANGUAGES =
            List.of("typescript", "javascript", "python", "go", "rust", "csharp", "ruby");

    private final RepoMapper repoMapper;
    private final CodeFileMapper codeFileMapper;
    private final CodeSymbolMapper codeSymbolMapper;
    private final CodeDependencyMapper codeDependencyMapper;
    private final RepoWorkspaceResolver workspaceResolver;
    private final PlatformTransactionManager txManager;

    /** node 可执行路径（默认走 PATH；可用 REPOLENS_PARSER_NODE_BIN 覆盖为绝对路径）。 */
    @Value("${repolens.parser.node-bin:node}")
    private String nodeBin;

    /** sidecar 目录（含 index.js 与 node_modules）；默认相对后端工作目录。 */
    @Value("${repolens.parser.sidecar-dir:tools/code-parser-sidecar}")
    private String sidecarDir;

    /** 单次解析超时（毫秒）。 */
    @Value("${repolens.parser.timeout-ms:120000}")
    private long timeoutMs;

    /**
     * 解析该 repo 的 TS/JS 文件并写入 code_symbol / code_dependency。
     *
     * @return 写入的符号数；任何前置缺失或失败均返回 0（fail-safe，不抛）。
     */
    public int parseRepository(Long repoId) {
        try {
            RepoEntity repo = repoMapper.selectById(repoId);
            if (repo == null) return 0;

            File index = new File(sidecarDir, "index.js");
            File nodeModules = new File(sidecarDir, "node_modules");
            if (!index.isFile() || !nodeModules.isDirectory()) {
                log.info("[Parser] sidecar 未就绪(index.js/node_modules 缺失，dir={}），跳过 TS/JS 解析。"
                        + " 首次使用请在该目录 `npm install`。", sidecarDir);
                return 0;
            }

            Path repoDir;
            try {
                repoDir = workspaceResolver.resolveRepoDirectory(repo);
            } catch (Exception e) {
                log.info("[Parser] repo {} 无本地快照目录，跳过 TS/JS 解析：{}", repoId, e.getMessage());
                return 0;
            }

            // 取该 repo 所有 code_file，过滤出 TS/JS 源文件（.ts 类文件 fileType 落 'TEXT'，故按扩展名判定）。
            List<CodeFileEntity> files = codeFileMapper.selectList(
                            Wrappers.<CodeFileEntity>lambdaQuery().eq(CodeFileEntity::getRepoId, repoId))
                    .stream()
                    .filter(f -> isSupported(f.getFilePath()))
                    .collect(Collectors.toList());
            if (files.isEmpty()) return 0;

            Map<String, Long> fileIdByPath = new HashMap<>();
            for (CodeFileEntity f : files) fileIdByPath.put(f.getFilePath(), f.getId());
            List<String> relPaths = new ArrayList<>(fileIdByPath.keySet());

            SidecarOutput output = invokeSidecar(repoDir, relPaths);
            if (output == null || !output.ok || output.results == null) return 0;

            return persist(repoId, output, fileIdByPath);
        } catch (Exception e) {
            log.warn("[Parser] TS/JS 解析异常 repoId={}（已忽略，不影响索引）：{}", repoId, e.getMessage());
            return 0;
        }
    }

    // ── 落库 ────────────────────────────────────────────────────────────────────

    private int persist(Long repoId, SidecarOutput output, Map<String, Long> fileIdByPath) {
        int[] symbolCount = {0};
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            // 只清理本语言（ts/js）旧数据，绝不动 Java 符号：先删依赖(按 source_symbol_id 属于本语言符号)，再删符号。
            List<Long> oldTsSymbolIds = codeSymbolMapper.selectList(
                            Wrappers.<CodeSymbolEntity>lambdaQuery()
                                    .eq(CodeSymbolEntity::getRepoId, repoId)
                                    .in(CodeSymbolEntity::getLanguage, SIDECAR_LANGUAGES))
                    .stream().map(CodeSymbolEntity::getId).collect(Collectors.toList());
            if (!oldTsSymbolIds.isEmpty()) {
                codeDependencyMapper.delete(Wrappers.<CodeDependencyEntity>lambdaQuery()
                        .in(CodeDependencyEntity::getSourceSymbolId, oldTsSymbolIds));
                codeSymbolMapper.deleteBatchIds(oldTsSymbolIds);
            }

            for (SidecarFileResult fr : output.results) {
                if (fr == null || fr.path == null) continue;
                Long fileId = fileIdByPath.get(fr.path);
                if (fileId == null) continue;
                String language = fr.language == null ? "typescript" : fr.language;

                // 先插符号，记 name → symbolId，供依赖按名解析出 source_symbol_id。
                Map<String, Long> symIdByName = new HashMap<>();
                if (fr.symbols != null) {
                    for (SidecarSymbol s : fr.symbols) {
                        if (s == null || s.name == null) continue;
                        SymbolType type = mapSymbolType(s.symbolType);
                        if (type == null) continue;
                        CodeSymbolEntity e = new CodeSymbolEntity();
                        e.setRepoId(repoId);
                        e.setFileId(fileId);
                        e.setLanguage(language);
                        e.setSymbolType(type);
                        e.setClassName(s.className);
                        e.setMethodName(s.methodName);
                        e.setSignature(s.signature);
                        e.setStartLine(s.startLine);
                        e.setEndLine(s.endLine);
                        codeSymbolMapper.insert(e);
                        symIdByName.put(s.name, e.getId());
                        symbolCount[0]++;
                    }
                }

                // 依赖：source 在本文件内按 name 解析出 symbolId；target 按名字符串存(与 Java 侧一致，图按名连边)。
                if (fr.dependencies != null) {
                    for (SidecarDependency d : fr.dependencies) {
                        if (d == null || d.sourceName == null || d.targetName == null) continue;
                        Long srcId = symIdByName.get(d.sourceName);
                        if (srcId == null) continue;
                        CodeDependencyEntity dep = new CodeDependencyEntity();
                        dep.setRepoId(repoId);
                        dep.setSourceSymbolId(srcId);
                        dep.setTargetSymbolName(d.targetName);
                        dep.setRelationType(d.relationType == null ? "CALL" : d.relationType);
                        dep.setConfidence(BigDecimal.valueOf(0.8)); // 语法级(无类型求解)，置信度低于 Java 精确解析
                        codeDependencyMapper.insert(dep);
                    }
                }
            }
        });
        log.info("[Parser] TS/JS 解析完成 repoId={}，写入 {} 个符号", repoId, symbolCount[0]);
        return symbolCount[0];
    }

    // ── sidecar 调用 ────────────────────────────────────────────────────────────

    private SidecarOutput invokeSidecar(Path repoDir, List<String> relPaths) {
        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(nodeBin,
                    new File(sidecarDir, "index.js").getAbsolutePath(),
                    repoDir.toAbsolutePath().toString());
            pb.redirectErrorStream(false);
            proc = pb.start();

            byte[] input = MAPPER.writeValueAsBytes(relPaths);
            try (var os = proc.getOutputStream()) {
                os.write(input);
            }
            byte[] out = proc.getInputStream().readAllBytes();

            boolean done = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!done) {
                proc.destroyForcibly();
                log.warn("[Parser] sidecar 超时({}ms)，已终止", timeoutMs);
                return null;
            }
            if (proc.exitValue() != 0) {
                String err = new String(proc.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                log.warn("[Parser] sidecar 退出码 {}：{}", proc.exitValue(), truncate(err, 300));
                return null;
            }
            return MAPPER.readValue(out, SidecarOutput.class);
        } catch (Exception e) {
            log.warn("[Parser] 调 sidecar 失败(node={})：{}", nodeBin, e.getMessage());
            if (proc != null) proc.destroyForcibly();
            return null;
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static boolean isSupported(String path) {
        if (path == null) return false;
        String p = path.toLowerCase(Locale.ROOT);
        return SUPPORTED_EXTS.stream().anyMatch(p::endsWith);
    }

    private static SymbolType mapSymbolType(String raw) {
        if (raw == null) return null;
        try {
            return SymbolType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return SymbolType.FUNCTION; // 未知类型兜底为 FUNCTION，保证节点可见
        }
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    // ── sidecar JSON DTO（契约见 tools/code-parser-sidecar/index.js 头注释）────────

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    static class SidecarOutput {
        public boolean ok;
        public String error;
        public List<SidecarFileResult> results;
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    static class SidecarFileResult {
        public String path;
        public String language;
        public List<SidecarSymbol> symbols;
        public List<SidecarDependency> dependencies;
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    static class SidecarSymbol {
        public String symbolType;
        public String name;
        public String className;
        public String methodName;
        public String signature;
        public Integer startLine;
        public Integer endLine;
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    static class SidecarDependency {
        public String sourceName;
        public String targetName;
        public String relationType;
    }
}
