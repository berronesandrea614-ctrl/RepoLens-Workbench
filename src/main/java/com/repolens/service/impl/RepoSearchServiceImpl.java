package com.repolens.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.CodeChunkEntity;
import com.repolens.domain.entity.RepoEntity;
import com.repolens.domain.vo.SearchMatchVO;
import com.repolens.domain.vo.SearchResultVO;
import com.repolens.mapper.CodeChunkMapper;
import com.repolens.mapper.RepoMapper;
import com.repolens.security.PermissionService;
import com.repolens.service.RepoSearchService;
import com.repolens.service.support.RepoWorkspaceResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 工作副本全文搜索：优先走 code_chunk 表索引（带 repo_id 过滤 + LIMIT 分页短路），
 * chunk 未索引时回退到 Files.walk 逐行扫描。
 */
@Service
@RequiredArgsConstructor
public class RepoSearchServiceImpl implements RepoSearchService {

    private static final int MAX_MATCHES = 500;
    private static final long MAX_FILE_BYTES = 1048576;
    private static final int MAX_LINE_CHARS = 200;

    private final PermissionService permissionService;
    private final RepoMapper repoMapper;
    private final RepoWorkspaceResolver repoWorkspaceResolver;
    private final CodeChunkMapper codeChunkMapper;

    @Override
    public SearchResultVO search(Long userId, Long repoId, String query, boolean caseSensitive, int offset, int limit) {
        if (!StringUtils.hasText(query) || query.trim().length() < 2) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Query must be at least 2 characters");
        }
        if (!permissionService.checkRepoPermission(userId, repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "No permission for repo " + repoId);
        }
        RepoEntity repo = repoMapper.selectById(repoId);
        if (repo == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "Repo not found");
        }

        // Validate and clamp pagination params
        offset = Math.max(0, offset);
        limit = Math.max(1, Math.min(limit, 500));

        String needle = query.trim();

        // Fast path: query code_chunk table if indexed data exists for this repo.
        if (hasIndexedChunks(repoId)) {
            return searchViaChunks(repoId, needle, caseSensitive, offset, limit);
        }

        // Fallback: scan the working-copy files (demo-scale repos without indexing).
        return searchViaFileWalk(repo, needle, caseSensitive, offset, limit);
    }

    /**
     * Check whether the repo has any indexed chunks in code_chunk table.
     * Uses LIMIT 1 to short-circuit the count scan.
     */
    private boolean hasIndexedChunks(Long repoId) {
        Long count = codeChunkMapper.selectCount(
                Wrappers.<CodeChunkEntity>lambdaQuery()
                        .eq(CodeChunkEntity::getRepoId, repoId)
                        .last("LIMIT 1"));
        return count != null && count > 0;
    }

    /**
     * DB-backed search: content LIKE + filePath LIKE with repo_id filter.
     * Chunks include start_line, so we can report the exact location.
     * For each matching chunk, we scan its content line-by-line to find all
     * matching lines (consistent with the file-walk behaviour).
     */
    private SearchResultVO searchViaChunks(Long repoId, String needle, boolean caseSensitive,
                                            int offset, int limit) {
        String needleCmp = caseSensitive ? needle : needle.toLowerCase(Locale.ROOT);

        // Fetch candidate chunks (content OR filePath matches); pull enough to hit MAX_MATCHES.
        List<CodeChunkEntity> chunks = codeChunkMapper.selectList(
                Wrappers.<CodeChunkEntity>lambdaQuery()
                        .eq(CodeChunkEntity::getRepoId, repoId)
                        .and(w -> w.like(CodeChunkEntity::getContent, needle)
                                .or().like(CodeChunkEntity::getFilePath, needle))
                        .orderByAsc(CodeChunkEntity::getFilePath)
                        .orderByAsc(CodeChunkEntity::getStartLine)
                        .last("LIMIT " + (MAX_MATCHES + 1)));

        List<SearchMatchVO> allMatches = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();  // dedup overlapping chunks by (filePath, lineNumber)
        boolean truncated = false;

        outer:
        for (CodeChunkEntity chunk : chunks) {
            String content = chunk.getContent() == null ? "" : chunk.getContent();
            String filePath = chunk.getFilePath() == null ? "" : chunk.getFilePath();
            int startLine = chunk.getStartLine() == null ? 1 : chunk.getStartLine();

            // Scan lines within the chunk to find exact match positions
            String[] lines = content.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                String cmp = caseSensitive ? line : line.toLowerCase(Locale.ROOT);
                int col = cmp.indexOf(needleCmp);
                if (col < 0) continue;
                String key = filePath + ":" + (startLine + i);
                if (!seenKeys.add(key)) continue;  // skip duplicate (filePath, lineNumber) combos
                if (allMatches.size() >= MAX_MATCHES) { truncated = true; break outer; }
                allMatches.add(SearchMatchVO.builder()
                        .filePath(filePath)
                        .line(startLine + i)
                        .lineContent(line.length() > MAX_LINE_CHARS ? line.substring(0, MAX_LINE_CHARS) : line)
                        .startCol(col)
                        .build());
            }
        }

        int endIdx = Math.min(offset + limit, allMatches.size());
        List<SearchMatchVO> paginatedMatches = endIdx > offset
                ? allMatches.subList(offset, endIdx) : new ArrayList<>();
        boolean hasMore = (offset + limit < allMatches.size()) || truncated;

        return SearchResultVO.builder()
                .query(needle).matches(paginatedMatches).matchCount(allMatches.size())
                .truncated(truncated).offset(offset).limit(limit).hasMore(hasMore)
                .build();
    }

    /**
     * Fallback file-walk search (original implementation, unchanged behaviour).
     */
    private SearchResultVO searchViaFileWalk(RepoEntity repo, String needle,
                                              boolean caseSensitive, int offset, int limit) {
        Path root = repoWorkspaceResolver.resolveReadDirectory(repo);
        String needleCmp = caseSensitive ? needle : needle.toLowerCase(Locale.ROOT);

        List<SearchMatchVO> allMatches = new ArrayList<>();
        boolean truncated = false;
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk.filter(Files::isRegularFile).sorted().toList();
            outer:
            for (Path file : files) {
                if (Files.isSymbolicLink(file)) continue;
                String rel = root.relativize(file).toString().replace('\\', '/');
                if (inGitDir(rel)) continue;
                try {
                    if (Files.size(file) > MAX_FILE_BYTES) continue;
                    byte[] bytes = Files.readAllBytes(file);
                    if (isBinary(bytes)) continue;
                    String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n", -1);
                    for (int i = 0; i < lines.length; i++) {
                        String line = lines[i];
                        if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                        String cmp = caseSensitive ? line : line.toLowerCase(Locale.ROOT);
                        int col = cmp.indexOf(needleCmp);
                        if (col < 0) continue;
                        if (allMatches.size() >= MAX_MATCHES) { truncated = true; break outer; }
                        allMatches.add(SearchMatchVO.builder()
                                .filePath(rel).line(i + 1)
                                .lineContent(line.length() > MAX_LINE_CHARS ? line.substring(0, MAX_LINE_CHARS) : line)
                                .startCol(col).build());
                    }
                } catch (IOException ignored) {
                }
            }
        } catch (IOException e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "Search failed: " + e.getMessage());
        }

        int endIdx = Math.min(offset + limit, allMatches.size());
        List<SearchMatchVO> paginatedMatches = endIdx > offset
                ? allMatches.subList(offset, endIdx) : new ArrayList<>();
        boolean hasMore = (offset + limit < allMatches.size()) || truncated;

        return SearchResultVO.builder()
                .query(needle).matches(paginatedMatches).matchCount(allMatches.size()).truncated(truncated)
                .offset(offset).limit(limit).hasMore(hasMore).build();
    }

    private static boolean isBinary(byte[] bytes) {
        int limit = Math.min(bytes.length, 8000);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) return true;
        }
        return false;
    }

    private static boolean inGitDir(String relativePath) {
        for (String segment : relativePath.split("/")) {
            if (".git".equals(segment)) return true;
        }
        return false;
    }
}
