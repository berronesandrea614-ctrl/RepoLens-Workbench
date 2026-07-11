package com.repolens.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.ChatMessageEntity;
import com.repolens.domain.entity.CodeFileEntity;
import com.repolens.domain.entity.CodeSymbolEntity;
import com.repolens.domain.entity.RequirementEntity;
import com.repolens.domain.entity.RequirementSymbolEntity;
import com.repolens.domain.vo.CodeGraphVO;
import com.repolens.domain.vo.CodeReferenceVO;
import com.repolens.domain.vo.GraphEdgeVO;
import com.repolens.domain.vo.GraphNodeVO;
import com.repolens.domain.vo.RequirementVO;
import com.repolens.mapper.ChatMessageMapper;
import com.repolens.mapper.CodeFileMapper;
import com.repolens.mapper.CodeSymbolMapper;
import com.repolens.mapper.RepoMapper;
import com.repolens.mapper.RequirementMapper;
import com.repolens.mapper.RequirementSymbolMapper;
import com.repolens.security.PermissionService;
import com.repolens.common.util.RepoUrlValidator;
import com.repolens.service.CodeGraphService;
import com.repolens.service.RequirementService;
import com.repolens.service.support.RepoWorkspaceResolver;
import com.repolens.service.impl.support.RequirementExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RequirementServiceImpl implements RequirementService {

    /** 种子符号上限，避免宽泛需求触发过多子图构建。 */
    private static final int MAX_SEEDS = 20;

    /** 合并后节点上限，超出即标记 truncated（复用图层规模约束）。 */
    private static final int MAX_MERGED_NODES = 150;

    /** Max bytes of a single file included in external-changes prompt (prevent oversized LLM calls). */
    private static final int MAX_FILE_CONTENT_BYTES = 4096;

    /** Max total prompt bytes for combined external file contents. */
    private static final int MAX_COMBINED_CONTENT_BYTES = 16384;

    /**
     * Merge window for consecutive external-changes bursts (minutes).
     * If the most recent external requirement for the same (userId, repoId) was updated/created
     * within this window, new file changes are merged into it instead of creating a new one.
     * Configurable via {@code repolens.insight.external-merge-window-min} (future).
     */
    private static final int EXTERNAL_MERGE_WINDOW_MIN = 30;

    private final RequirementMapper requirementMapper;
    private final RequirementSymbolMapper requirementSymbolMapper;
    private final CodeSymbolMapper codeSymbolMapper;
    private final PermissionService permissionService;
    private final CodeFileMapper codeFileMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final CodeGraphService codeGraphService;
    private final RequirementExtractor requirementExtractor;
    private final RepoWorkspaceResolver workspaceResolver;
    private final RepoUrlValidator repoUrlValidator;
    private final RepoMapper repoMapper;
    private final PlatformTransactionManager txManager;

    @Override
    public List<RequirementVO> list(Long userId, Long repoId) {
        checkPermission(userId, repoId);
        List<RequirementEntity> requirements = requirementMapper.selectList(
                Wrappers.<RequirementEntity>lambdaQuery()
                        .eq(RequirementEntity::getUserId, userId)
                        .eq(RequirementEntity::getRepoId, repoId));
        return requirements.stream()
                .sorted(Comparator.comparing(RequirementEntity::getCreatedAt,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(RequirementEntity::getId,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .reversed())
                .map(r -> toVO(r, fileCount(r.getId())))
                .collect(Collectors.toList());
    }

    @Override
    public RequirementVO get(Long userId, Long repoId, Long requirementId) {
        checkPermission(userId, repoId);
        RequirementEntity requirement = loadOwned(userId, repoId, requirementId);
        return toVO(requirement, fileCount(requirement.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, Long repoId, Long requirementId) {
        checkPermission(userId, repoId);
        RequirementEntity requirement = loadOwned(userId, repoId, requirementId);
        requirementSymbolMapper.delete(Wrappers.<RequirementSymbolEntity>lambdaQuery()
                .eq(RequirementSymbolEntity::getRequirementId, requirement.getId()));
        requirementMapper.deleteById(requirement.getId());
    }

    @Override
    public RequirementVO enqueue(Long userId, Long repoId, Long sessionId, String title, String summary,
                                 List<CodeReferenceVO> references, Long agentRunId, String approach) {
        // TransactionTemplate participates in surrounding transaction if active (REQUIRED semantics),
        // or starts a new one. Fixes self-call bypass from summarize().
        return new TransactionTemplate(txManager).execute(status -> {
            RequirementEntity requirement = new RequirementEntity();
            requirement.setUserId(userId);
            requirement.setRepoId(repoId);
            requirement.setSessionId(sessionId);
            requirement.setAgentRunId(agentRunId);
            requirement.setTitle(title);
            requirement.setSummary(summary);
            requirement.setApproach(approach);
            requirement.setStatus("SUMMARIZED");
            requirement.setCreatedAt(LocalDateTime.now());
            requirementMapper.insert(requirement);
            Long requirementId = requirement.getId();

            if (references == null || references.isEmpty()) {
                return toVO(requirement, 0);
            }
            // 按 (filePath, startLine) 去重，避免同一位点重复落库；顺带统计去重文件数。
            Set<String> seen = new HashSet<>();
            Set<String> filePaths = new HashSet<>();
            for (CodeReferenceVO ref : references) {
                if (ref == null) {
                    continue;
                }
                String dedupKey = ref.getFilePath() + ":" + ref.getStartLine();
                if (!seen.add(dedupKey)) {
                    continue;
                }
                Long symbolId = resolveSymbolId(repoId, ref.getClassName(), ref.getMethodName());
                RequirementSymbolEntity symbol = new RequirementSymbolEntity();
                symbol.setRequirementId(requirementId);
                symbol.setSymbolId(symbolId);
                symbol.setFilePath(ref.getFilePath());
                symbol.setStartLine(ref.getStartLine());
                requirementSymbolMapper.insert(symbol);
                if (StringUtils.hasText(ref.getFilePath())) {
                    filePaths.add(ref.getFilePath());
                }
            }
            return toVO(requirement, filePaths.size());
        });
    }

    @Override
    public CodeGraphVO requirementGraph(Long userId, Long repoId, Long requirementId) {
        checkPermission(userId, repoId);
        RequirementEntity requirement = loadOwned(userId, repoId, requirementId);

        List<Long> seeds = collectSeedSymbolIds(repoId, requirement.getId());
        if (seeds.isEmpty()) {
            return CodeGraphVO.builder()
                    .rootId(null)
                    .nodes(new ArrayList<>())
                    .edges(new ArrayList<>())
                    .nodeCount(0)
                    .edgeCount(0)
                    .truncated(false)
                    .build();
        }

        // 按 id 并集合并各种子的子图，保留首次出现的节点/边。
        Map<String, GraphNodeVO> nodes = new LinkedHashMap<>();
        Map<String, GraphEdgeVO> edges = new LinkedHashMap<>();
        boolean truncated = false;
        for (Long seedId : seeds) {
            CodeGraphVO sub;
            try {
                sub = codeGraphService.buildGraph(userId, repoId, seedId, "callees", 2, 0.0);
            } catch (Exception ex) {
                // 单个种子子图失败不影响整体：跳过继续。
                log.warn("requirement subgraph build failed, skip seed={}, err={}", seedId, ex.getMessage());
                continue;
            }
            if (sub == null) {
                continue;
            }
            if (sub.getNodes() != null) {
                for (GraphNodeVO n : sub.getNodes()) {
                    if (n != null && n.getId() != null) {
                        nodes.putIfAbsent(n.getId(), n);
                    }
                }
            }
            if (sub.getEdges() != null) {
                for (GraphEdgeVO e : sub.getEdges()) {
                    if (e != null && e.getId() != null) {
                        edges.putIfAbsent(e.getId(), e);
                    }
                }
            }
            if (sub.isTruncated()) {
                truncated = true;
            }
        }
        if (nodes.size() > MAX_MERGED_NODES) {
            truncated = true;
        }
        return CodeGraphVO.builder()
                .rootId(String.valueOf(seeds.get(0)))
                .nodes(new ArrayList<>(nodes.values()))
                .edges(new ArrayList<>(edges.values()))
                .nodeCount(nodes.size())
                .edgeCount(edges.size())
                .truncated(truncated)
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RequirementVO summarize(Long userId, Long repoId, Long sessionId) {
        checkPermission(userId, repoId);
        List<ChatMessageEntity> messages = chatMessageMapper.selectList(
                Wrappers.<ChatMessageEntity>lambdaQuery()
                        .eq(ChatMessageEntity::getSessionId, sessionId)
                        .orderByAsc(ChatMessageEntity::getId));
        String question = latestContentByRole(messages, "USER");
        String answer = latestContentByRole(messages, "ASSISTANT");
        Optional<RequirementExtractor.ReqNote> note = requirementExtractor.extract(question, answer);
        if (note.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "No requirement could be summarized from session " + sessionId);
        }
        // 手动路径不携带结构化引用，以空引用沉淀（symbol 可空，仅记录标题 + 摘要）。
        // agentRunId/approach 来自抽取器，无计划路径时均为 null。
        return enqueue(userId, repoId, sessionId,
                note.get().title(), note.get().summary(), List.of(),
                null, note.get().approach());
    }

    @Override
    public Optional<RequirementVO> summarizeExternal(Long userId, Long repoId,
                                                      List<String> changedFiles,
                                                      String realDir, String sessionHint) {
        checkPermission(userId, repoId);
        if (changedFiles == null || changedFiles.isEmpty()) {
            log.debug("[ExternalChanges] No changed files provided, skipping summarization repoId={}", repoId);
            return Optional.empty();
        }
        // Determine the root directory for file reading.
        Path rootPath = resolveRootPath(repoId, realDir);

        // B1: Find recent external requirement to merge into (same repo + merge window).
        RequirementEntity mergeTarget = findRecentExternalRequirement(userId, repoId);
        Set<String> existingPaths = new LinkedHashSet<>();
        if (mergeTarget != null) {
            List<RequirementSymbolEntity> existingSyms = requirementSymbolMapper.selectList(
                    Wrappers.<RequirementSymbolEntity>lambdaQuery()
                            .eq(RequirementSymbolEntity::getRequirementId, mergeTarget.getId()));
            for (RequirementSymbolEntity sym : existingSyms) {
                if (StringUtils.hasText(sym.getFilePath())) {
                    existingPaths.add(sym.getFilePath());
                }
            }
            log.debug("[ExternalChanges] Merge candidate found reqId={}, existingFiles={}",
                    mergeTarget.getId(), existingPaths.size());
        }

        // Merge: existing paths + new paths, deduplicated (existing first, preserving order).
        LinkedHashSet<String> allPathsSet = new LinkedHashSet<>(existingPaths);
        for (String f : changedFiles) {
            if (StringUtils.hasText(f)) allPathsSet.add(f);
        }
        List<String> allPaths = new ArrayList<>(allPathsSet);

        // Read file contents for ALL merged paths — fail-safe per file.
        StringBuilder combined = new StringBuilder();
        List<String> readableFiles = new ArrayList<>();
        for (String relPath : allPaths) {
            if (!StringUtils.hasText(relPath)) continue;
            try {
                Path target = safeResolveExternalFile(rootPath, relPath);
                if (target == null || !Files.exists(target) || Files.isDirectory(target)) {
                    readableFiles.add(relPath); // include path even if unreadable
                    continue;
                }
                byte[] bytes = Files.readAllBytes(target);
                String content = new String(bytes, StandardCharsets.UTF_8);
                if (content.length() > MAX_FILE_CONTENT_BYTES) {
                    content = content.substring(0, MAX_FILE_CONTENT_BYTES) + "\n...(truncated)";
                }
                combined.append("=== ").append(relPath).append(" ===\n")
                        .append(content).append("\n\n");
                readableFiles.add(relPath);
            } catch (Exception e) {
                log.warn("[ExternalChanges] Failed to read file {}, skip. err={}", relPath, e.getMessage());
                readableFiles.add(relPath); // still include path in prompt
            }
            if (combined.length() > MAX_COMBINED_CONTENT_BYTES) {
                combined.append("...(remaining files omitted due to size limit)");
                break;
            }
        }
        if (StringUtils.hasText(sessionHint)) {
            combined.append("Session hint: ").append(sessionHint);
        }

        Optional<RequirementExtractor.ReqNote> note =
                requirementExtractor.extractFromExternalChanges(readableFiles, combined.toString());
        if (note.isEmpty()) {
            log.debug("[ExternalChanges] No requirement extracted for repoId={}", repoId);
            return Optional.empty();
        }

        // Build refs for all readable merged paths.
        List<CodeReferenceVO> refs = readableFiles.stream()
                .filter(StringUtils::hasText)
                .map(p -> CodeReferenceVO.builder().filePath(p).startLine(null).build())
                .collect(Collectors.toList());

        if (mergeTarget != null) {
            // B1 merge path: update existing requirement + replace symbols.
            RequirementEntity target = mergeTarget;
            RequirementVO vo = new TransactionTemplate(txManager).execute(status -> {
                target.setTitle(note.get().title());
                target.setSummary(note.get().summary());
                target.setApproach(note.get().approach());
                target.setUpdatedAt(LocalDateTime.now());
                requirementMapper.updateById(target);

                // Replace symbols with merged set.
                requirementSymbolMapper.delete(Wrappers.<RequirementSymbolEntity>lambdaQuery()
                        .eq(RequirementSymbolEntity::getRequirementId, target.getId()));
                Set<String> seenPaths = new HashSet<>();
                for (CodeReferenceVO ref : refs) {
                    if (ref == null || !StringUtils.hasText(ref.getFilePath())) continue;
                    if (!seenPaths.add(ref.getFilePath())) continue;
                    RequirementSymbolEntity sym = new RequirementSymbolEntity();
                    sym.setRequirementId(target.getId());
                    sym.setSymbolId(null);
                    sym.setFilePath(ref.getFilePath());
                    sym.setStartLine(null);
                    requirementSymbolMapper.insert(sym);
                }
                return toVO(target, seenPaths.size());
            });
            log.info("[ExternalChanges] Requirement merged repoId={}, reqId={}, totalFiles={}",
                    repoId, vo != null ? vo.getId() : null, readableFiles.size());
            return Optional.ofNullable(vo);
        }

        // New requirement path: insert.
        RequirementVO vo = new TransactionTemplate(txManager).execute(status -> {
            RequirementEntity req = new RequirementEntity();
            req.setUserId(userId);
            req.setRepoId(repoId);
            req.setSessionId(null);
            req.setAgentRunId(null);
            req.setTitle(note.get().title());
            req.setSummary(note.get().summary());
            req.setApproach(note.get().approach());
            req.setStatus("SUMMARIZED");
            req.setSource("external");
            req.setCreatedAt(LocalDateTime.now());
            requirementMapper.insert(req);
            Long requirementId = req.getId();

            Set<String> seenPaths = new HashSet<>();
            for (CodeReferenceVO ref : refs) {
                if (ref == null || !StringUtils.hasText(ref.getFilePath())) continue;
                if (!seenPaths.add(ref.getFilePath())) continue;
                RequirementSymbolEntity sym = new RequirementSymbolEntity();
                sym.setRequirementId(requirementId);
                sym.setSymbolId(null);
                sym.setFilePath(ref.getFilePath());
                sym.setStartLine(null);
                requirementSymbolMapper.insert(sym);
            }
            return toVO(req, seenPaths.size());
        });

        log.info("[ExternalChanges] Requirement summarized repoId={}, reqId={}, files={}",
                repoId, vo != null ? vo.getId() : null, readableFiles.size());
        return Optional.ofNullable(vo);
    }

    /**
     * Find the most recent {@code source="external"} requirement for this (userId, repoId)
     * that falls within the merge window ({@link #EXTERNAL_MERGE_WINDOW_MIN} minutes).
     * Falls back to {@code createdAt} when {@code updatedAt} is null (first burst after insert).
     * Returns {@code null} if none exists or none is within the window.
     */
    private RequirementEntity findRecentExternalRequirement(Long userId, Long repoId) {
        try {
            List<RequirementEntity> candidates = requirementMapper.selectList(
                    Wrappers.<RequirementEntity>lambdaQuery()
                            .eq(RequirementEntity::getUserId, userId)
                            .eq(RequirementEntity::getRepoId, repoId)
                            .eq(RequirementEntity::getSource, "external"));
            if (candidates == null || candidates.isEmpty()) return null;

            LocalDateTime windowStart = LocalDateTime.now().minusMinutes(EXTERNAL_MERGE_WINDOW_MIN);
            return candidates.stream()
                    .filter(r -> {
                        // Use updatedAt if available, otherwise fall back to createdAt.
                        LocalDateTime ts = r.getUpdatedAt() != null ? r.getUpdatedAt() : r.getCreatedAt();
                        return ts != null && !ts.isBefore(windowStart);
                    })
                    .max(Comparator.comparing(r -> {
                        LocalDateTime ts = r.getUpdatedAt() != null ? r.getUpdatedAt() : r.getCreatedAt();
                        return ts;
                    }))
                    .orElse(null);
        } catch (Exception e) {
            log.warn("[ExternalChanges] findRecentExternalRequirement failed, skipping merge. err={}",
                    e.getMessage());
            return null;
        }
    }

    /**
     * Resolve the root directory for reading external-change files.
     * The root is derived SERVER-SIDE from the repository record in the database.
     * The client-supplied {@code realDir} is intentionally ignored to prevent
     * path traversal / arbitrary file exfiltration attacks — an attacker could
     * otherwise supply {@code realDir="/etc"} to read host files via the LLM.
     * <p>
     * If the repo's {@code repoUrl} is a local {@code file://} URL, the decoded
     * local path is used directly as root.  For all other repos, the workspace
     * snapshot directory resolved by {@link RepoWorkspaceResolver} is used.
     */
    private Path resolveRootPath(Long repoId, String realDir) {
        // SECURITY: client-supplied realDir is intentionally NOT trusted.
        try {
            com.repolens.domain.entity.RepoEntity repo = repoMapper.selectById(repoId);
            if (repo == null) {
                log.warn("[ExternalChanges] Repo not found for repoId={}, cannot resolve root path", repoId);
                return null;
            }
            String repoUrl = repo.getRepoUrl();
            if (StringUtils.hasText(repoUrl) && repoUrl.startsWith("file://")) {
                try {
                    Path localPath = repoUrlValidator.resolveLocalRepoPath(repoUrl);
                    if (Files.isDirectory(localPath)) {
                        return localPath;
                    }
                    log.warn("[ExternalChanges] file:// path is not a directory for repoId={}: {}", repoId, localPath);
                } catch (Exception e) {
                    log.warn("[ExternalChanges] Failed to resolve file:// path for repoId={}: {}", repoId, e.getMessage());
                }
            }
            // Non-file:// repo (remote clone or snapshot): use workspace resolver.
            return workspaceResolver.resolveRepoDirectory(repo);
        } catch (Exception e) {
            log.warn("[ExternalChanges] Cannot resolve root path for repoId={}: {}", repoId, e.getMessage());
            return null;
        }
    }

    /**
     * Safely resolve a relative file path under the given root, preventing path traversal.
     * Returns null if the path is unsafe or rootPath is null.
     */
    private Path safeResolveExternalFile(Path rootPath, String relativePath) {
        if (rootPath == null || !StringUtils.hasText(relativePath)) return null;
        try {
            Path resolved = rootPath.resolve(relativePath).toAbsolutePath().normalize();
            if (!resolved.startsWith(rootPath)) {
                log.warn("[ExternalChanges] Path traversal rejected: {}", relativePath);
                return null;
            }
            return resolved;
        } catch (Exception e) {
            log.warn("[ExternalChanges] Path resolution failed for {}: {}", relativePath, e.getMessage());
            return null;
        }
    }

    /** 取该会话中指定角色的最新（id 最大，即列表末尾）一条消息内容；无则返回 null。 */
    private String latestContentByRole(List<ChatMessageEntity> messages, String role) {
        if (messages == null) {
            return null;
        }
        String content = null;
        for (ChatMessageEntity m : messages) {
            if (m != null && role.equalsIgnoreCase(m.getRole())) {
                content = m.getContent();
            }
        }
        return content;
    }

    /**
     * 收集去重后的种子符号 id（上限 {@link #MAX_SEEDS}）：symbolId 非空直接用；为空但有 filePath 时，
     * 解析该文件下的全部符号 id 作为种子。
     */
    private List<Long> collectSeedSymbolIds(Long repoId, Long requirementId) {
        List<RequirementSymbolEntity> rows = requirementSymbolMapper.selectList(
                Wrappers.<RequirementSymbolEntity>lambdaQuery()
                        .eq(RequirementSymbolEntity::getRequirementId, requirementId));
        LinkedHashSet<Long> seeds = new LinkedHashSet<>();
        if (rows == null) {
            return new ArrayList<>();
        }
        for (RequirementSymbolEntity row : rows) {
            if (seeds.size() >= MAX_SEEDS) {
                break;
            }
            if (row.getSymbolId() != null) {
                seeds.add(row.getSymbolId());
            } else if (StringUtils.hasText(row.getFilePath())) {
                for (Long id : resolveSeedsByFile(repoId, row.getFilePath())) {
                    if (seeds.size() >= MAX_SEEDS) {
                        break;
                    }
                    seeds.add(id);
                }
            }
        }
        return new ArrayList<>(seeds);
    }

    /** 解析某文件下的全部符号 id：先按 repoId + filePath 定位 code_file，再取其下 code_symbol。 */
    private List<Long> resolveSeedsByFile(Long repoId, String filePath) {
        List<CodeFileEntity> files = codeFileMapper.selectList(
                Wrappers.<CodeFileEntity>lambdaQuery()
                        .eq(CodeFileEntity::getRepoId, repoId)
                        .eq(CodeFileEntity::getFilePath, filePath));
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        List<Long> fileIds = files.stream()
                .map(CodeFileEntity::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (fileIds.isEmpty()) {
            return List.of();
        }
        List<CodeSymbolEntity> symbols = codeSymbolMapper.selectList(
                Wrappers.<CodeSymbolEntity>lambdaQuery()
                        .eq(CodeSymbolEntity::getRepoId, repoId)
                        .in(CodeSymbolEntity::getFileId, fileIds));
        if (symbols == null) {
            return List.of();
        }
        return symbols.stream()
                .map(CodeSymbolEntity::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 尽力解析 symbolId：按 repoId + className + methodName 查 code_symbol 取最佳（首条）匹配。
     * className 与 methodName 均缺省时无从定位，直接返回 null（仅定位到文件行）。
     */
    private Long resolveSymbolId(Long repoId, String className, String methodName) {
        if (repoId == null || (!StringUtils.hasText(className) && !StringUtils.hasText(methodName))) {
            return null;
        }
        var query = Wrappers.<CodeSymbolEntity>lambdaQuery().eq(CodeSymbolEntity::getRepoId, repoId);
        if (StringUtils.hasText(className)) {
            query.eq(CodeSymbolEntity::getClassName, className);
        }
        if (StringUtils.hasText(methodName)) {
            query.eq(CodeSymbolEntity::getMethodName, methodName);
        }
        List<CodeSymbolEntity> matches = codeSymbolMapper.selectList(query);
        return (matches == null || matches.isEmpty()) ? null : matches.get(0).getId();
    }

    private void checkPermission(Long userId, Long repoId) {
        if (!permissionService.checkRepoPermission(userId, repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "No permission for repo " + repoId);
        }
    }

    /** 加载需求并校验归属；不存在 NOT_FOUND，不属于该 (user, repo) FORBIDDEN。 */
    private RequirementEntity loadOwned(Long userId, Long repoId, Long requirementId) {
        RequirementEntity requirement = requirementMapper.selectById(requirementId);
        if (requirement == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "Requirement not found: " + requirementId);
        }
        if (!userId.equals(requirement.getUserId()) || !repoId.equals(requirement.getRepoId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "Requirement does not belong to repo " + repoId);
        }
        return requirement;
    }

    /** 该需求关联的去重文件数 = distinct requirement_symbol.filePath。 */
    private int fileCount(Long requirementId) {
        List<RequirementSymbolEntity> symbols = requirementSymbolMapper.selectList(
                Wrappers.<RequirementSymbolEntity>lambdaQuery()
                        .eq(RequirementSymbolEntity::getRequirementId, requirementId));
        return (int) symbols.stream()
                .map(RequirementSymbolEntity::getFilePath)
                .filter(p -> p != null && !p.isEmpty())
                .distinct()
                .count();
    }

    private RequirementVO toVO(RequirementEntity r, int fileCount) {
        return RequirementVO.builder()
                .id(r.getId())
                .title(r.getTitle())
                .summary(r.getSummary())
                .status(r.getStatus())
                .source(r.getSource())
                .fileCount(fileCount)
                .createdAt(r.getCreatedAt())
                .build();
    }
}
