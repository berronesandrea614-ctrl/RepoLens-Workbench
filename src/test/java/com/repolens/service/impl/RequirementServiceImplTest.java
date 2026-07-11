package com.repolens.service.impl;

import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.common.util.RepoUrlValidator;
import com.repolens.domain.entity.CodeFileEntity;
import com.repolens.domain.entity.CodeSymbolEntity;
import com.repolens.domain.entity.RepoEntity;
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
import com.repolens.service.CodeGraphService;
import com.repolens.service.impl.support.RequirementExtractor;
import com.repolens.service.support.RepoWorkspaceResolver;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 需求存储层单测（mock mapper + PermissionService，无真实 DB）。验证：
 * (a) list 返回 VO，fileCount = 去重 filePath 数；
 * (b) delete 校验归属后删除需求及其 requirement_symbol；
 * (c) get 命中不属于 (user, repo) 的需求抛 FORBIDDEN。
 */
@ExtendWith(MockitoExtension.class)
class RequirementServiceImplTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 3, 12, 0, 0);
    private static final Long USER = 1L;
    private static final Long REPO = 2L;

    @Mock
    private RequirementMapper requirementMapper;
    @Mock
    private RequirementSymbolMapper requirementSymbolMapper;
    @Mock
    private CodeSymbolMapper codeSymbolMapper;
    @Mock
    private PermissionService permissionService;
    @Mock
    private CodeFileMapper codeFileMapper;
    @Mock
    private ChatMessageMapper chatMessageMapper;
    @Mock
    private CodeGraphService codeGraphService;
    @Mock
    private RequirementExtractor requirementExtractor;
    @Mock
    private RepoWorkspaceResolver workspaceResolver;
    @Mock
    private RepoUrlValidator repoUrlValidator;
    @Mock
    private RepoMapper repoMapper;
    @Mock
    private org.springframework.transaction.PlatformTransactionManager txManager;

    private RequirementServiceImpl newService() {
        return new RequirementServiceImpl(requirementMapper, requirementSymbolMapper, codeSymbolMapper,
                permissionService, codeFileMapper, chatMessageMapper, codeGraphService,
                requirementExtractor, workspaceResolver, repoUrlValidator, repoMapper, txManager);
    }

    private RequirementEntity requirement(long id, Long userId, Long repoId, LocalDateTime createdAt) {
        RequirementEntity r = new RequirementEntity();
        r.setId(id);
        r.setUserId(userId);
        r.setRepoId(repoId);
        r.setSessionId(9L);
        r.setTitle("title-" + id);
        r.setSummary("summary-" + id);
        r.setStatus("SUMMARIZED");
        r.setCreatedAt(createdAt);
        return r;
    }

    private RequirementSymbolEntity symbol(long id, long reqId, String filePath) {
        RequirementSymbolEntity s = new RequirementSymbolEntity();
        s.setId(id);
        s.setRequirementId(reqId);
        s.setFilePath(filePath);
        s.setStartLine(1);
        return s;
    }

    @Test
    void list_shouldReturnVOsWithDistinctFileCount() {
        when(permissionService.checkRepoPermission(USER, REPO)).thenReturn(true);
        RequirementEntity r10 = requirement(10, USER, REPO, NOW.minusDays(2));
        RequirementEntity r11 = requirement(11, USER, REPO, NOW.minusDays(1));
        when(requirementMapper.selectList(any())).thenReturn(List.of(r10, r11));
        // 排序后最新在前，先查 r11（无符号 -> 0），再查 r10（A/A/B -> 2 distinct）。
        when(requirementSymbolMapper.selectList(any())).thenReturn(
                List.of(),
                List.of(symbol(1, 10, "A.java"), symbol(2, 10, "A.java"), symbol(3, 10, "B.java")));

        List<RequirementVO> result = newService().list(USER, REPO);

        Assertions.assertEquals(2, result.size());
        // 最新在前：r11 先
        Assertions.assertEquals(11L, result.get(0).getId());
        Assertions.assertEquals(0, result.get(0).getFileCount());
        Assertions.assertEquals(10L, result.get(1).getId());
        Assertions.assertEquals(2, result.get(1).getFileCount());
        Assertions.assertEquals("title-10", result.get(1).getTitle());
        Assertions.assertEquals("SUMMARIZED", result.get(1).getStatus());
    }

    @Test
    void delete_shouldRemoveRequirementAndSymbolsWhenOwned() {
        when(permissionService.checkRepoPermission(USER, REPO)).thenReturn(true);
        when(requirementMapper.selectById(10L)).thenReturn(requirement(10, USER, REPO, NOW));

        newService().delete(USER, REPO, 10L);

        verify(requirementSymbolMapper, times(1)).delete(any());
        verify(requirementMapper, times(1)).deleteById(10L);
    }

    @Test
    void get_shouldThrowForbiddenWhenNotOwnedByUserRepo() {
        when(permissionService.checkRepoPermission(USER, REPO)).thenReturn(true);
        // 需求归属另一个 repo(99) -> 不属于 (USER, REPO) -> FORBIDDEN
        when(requirementMapper.selectById(10L)).thenReturn(requirement(10, USER, 99L, NOW));

        BizException ex = Assertions.assertThrows(BizException.class,
                () -> newService().get(USER, REPO, 10L));
        Assertions.assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
    }

    @Test
    void get_shouldThrowNotFoundWhenMissing() {
        when(permissionService.checkRepoPermission(USER, REPO)).thenReturn(true);
        when(requirementMapper.selectById(10L)).thenReturn(null);

        BizException ex = Assertions.assertThrows(BizException.class,
                () -> newService().get(USER, REPO, 10L));
        Assertions.assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void enqueue_shouldInsertRequirementAndDistinctSymbolsResolvingSymbolId() {
        // requirement.insert 回填自增 id，供后续 requirement_symbol 关联。
        when(requirementMapper.insert(any(RequirementEntity.class))).thenAnswer(inv -> {
            Object entity = inv.getArgument(0);
            ReflectionTestUtils.setField(entity, "id", 77L);
            return 1;
        });
        // 仅当带 className/methodName 时才查 code_symbol；此处命中返回符号 id=5。
        CodeSymbolEntity cs = new CodeSymbolEntity();
        cs.setId(5L);
        when(codeSymbolMapper.selectList(any())).thenReturn(List.of(cs));

        List<CodeReferenceVO> refs = List.of(
                // 可解析：className+methodName 命中 code_symbol
                CodeReferenceVO.builder().filePath("A.java").startLine(10).className("A").methodName("m").build(),
                // 与上一条同位点 (A.java, 10) -> 去重跳过
                CodeReferenceVO.builder().filePath("A.java").startLine(10).className("A").methodName("m").build(),
                // 无 className/methodName -> symbolId 留 null，且不查 code_symbol
                CodeReferenceVO.builder().filePath("B.java").startLine(20).build());

        newService().enqueue(USER, REPO, 9L, "标题X", "摘要X", refs, 42L, "整体思路");

        // 一条需求 + 两个不同位点的 requirement_symbol（A.java:10 去重后仅一条）。
        ArgumentCaptor<RequirementEntity> reqCaptor = ArgumentCaptor.forClass(RequirementEntity.class);
        verify(requirementMapper, times(1)).insert(reqCaptor.capture());
        Assertions.assertEquals("SUMMARIZED", reqCaptor.getValue().getStatus());
        Assertions.assertEquals("标题X", reqCaptor.getValue().getTitle());
        Assertions.assertNotNull(reqCaptor.getValue().getCreatedAt());
        // 新字段校验：agentRunId + approach 应落库。
        Assertions.assertEquals(42L, reqCaptor.getValue().getAgentRunId());
        Assertions.assertEquals("整体思路", reqCaptor.getValue().getApproach());

        ArgumentCaptor<RequirementSymbolEntity> symCaptor = ArgumentCaptor.forClass(RequirementSymbolEntity.class);
        verify(requirementSymbolMapper, times(2)).insert(symCaptor.capture());
        List<RequirementSymbolEntity> inserted = symCaptor.getAllValues();

        RequirementSymbolEntity a = inserted.stream().filter(s -> "A.java".equals(s.getFilePath())).findFirst().orElseThrow();
        Assertions.assertEquals(77L, a.getRequirementId());
        Assertions.assertEquals(5L, a.getSymbolId());
        Assertions.assertEquals(10, a.getStartLine());

        RequirementSymbolEntity b = inserted.stream().filter(s -> "B.java".equals(s.getFilePath())).findFirst().orElseThrow();
        Assertions.assertNull(b.getSymbolId());
        Assertions.assertEquals(20, b.getStartLine());

        // 仅 A.java 那条带符号信息才查 code_symbol，共一次。
        verify(codeSymbolMapper, times(1)).selectList(any());
    }

    @Test
    void enqueue_shouldCreateRequirementOnlyWhenReferencesEmpty() {
        when(requirementMapper.insert(any(RequirementEntity.class))).thenAnswer(inv -> {
            Object entity = inv.getArgument(0);
            ReflectionTestUtils.setField(entity, "id", 88L);
            return 1;
        });

        newService().enqueue(USER, REPO, 9L, "只有标题", "摘要", List.of(), null, null);

        verify(requirementMapper, times(1)).insert(any(RequirementEntity.class));
        verify(requirementSymbolMapper, never()).insert(any(RequirementSymbolEntity.class));
    }

    private RequirementSymbolEntity symbolWithId(long id, long reqId, Long symbolId, String filePath) {
        RequirementSymbolEntity s = new RequirementSymbolEntity();
        s.setId(id);
        s.setRequirementId(reqId);
        s.setSymbolId(symbolId);
        s.setFilePath(filePath);
        s.setStartLine(1);
        return s;
    }

    private GraphNodeVO node(String id) {
        return GraphNodeVO.builder().id(id).label("n-" + id).build();
    }

    private GraphEdgeVO edge(String id, String source, String target) {
        return GraphEdgeVO.builder().id(id).source(source).target(target).relationType("CALLS").build();
    }

    private CodeGraphVO graph(String rootId, List<GraphNodeVO> nodes, List<GraphEdgeVO> edges, boolean truncated) {
        return CodeGraphVO.builder()
                .rootId(rootId).nodes(nodes).edges(edges)
                .nodeCount(nodes.size()).edgeCount(edges.size()).truncated(truncated).build();
    }

    @Test
    void requirementGraph_shouldMergeSeedSubgraphsUnioningNodesAndEdges() {
        when(permissionService.checkRepoPermission(USER, REPO)).thenReturn(true);
        when(requirementMapper.selectById(10L)).thenReturn(requirement(10, USER, REPO, NOW));
        // 两个种子（symbolId 100、200），各自的子图有重叠节点 n2 与重叠边 e1，应被去重。
        when(requirementSymbolMapper.selectList(any())).thenReturn(List.of(
                symbolWithId(1, 10, 100L, "A.java"),
                symbolWithId(2, 10, 200L, "B.java")));
        when(codeGraphService.buildGraph(eq(USER), eq(REPO), eq(100L), eq("callees"), eq(2), eq(0.0)))
                .thenReturn(graph("100", List.of(node("n1"), node("n2")), List.of(edge("e1", "n1", "n2")), false));
        when(codeGraphService.buildGraph(eq(USER), eq(REPO), eq(200L), eq("callees"), eq(2), eq(0.0)))
                .thenReturn(graph("200", List.of(node("n2"), node("n3")), List.of(edge("e1", "n1", "n2"), edge("e2", "n2", "n3")), false));

        CodeGraphVO merged = newService().requirementGraph(USER, REPO, 10L);

        Assertions.assertEquals("100", merged.getRootId());
        Assertions.assertEquals(3, merged.getNodeCount());
        Assertions.assertEquals(3, merged.getNodes().size());
        Assertions.assertEquals(2, merged.getEdgeCount());
        Assertions.assertEquals(2, merged.getEdges().size());
        Assertions.assertFalse(merged.isTruncated());
        List<String> nodeIds = merged.getNodes().stream().map(GraphNodeVO::getId).collect(java.util.stream.Collectors.toList());
        Assertions.assertTrue(nodeIds.containsAll(List.of("n1", "n2", "n3")));
    }

    @Test
    void requirementGraph_shouldReturnEmptyGraphWhenNoSeedsResolve() {
        when(permissionService.checkRepoPermission(USER, REPO)).thenReturn(true);
        when(requirementMapper.selectById(10L)).thenReturn(requirement(10, USER, REPO, NOW));
        when(requirementSymbolMapper.selectList(any())).thenReturn(List.of());

        CodeGraphVO g = newService().requirementGraph(USER, REPO, 10L);

        Assertions.assertNull(g.getRootId());
        Assertions.assertEquals(0, g.getNodeCount());
        Assertions.assertTrue(g.getNodes().isEmpty());
        Assertions.assertEquals(0, g.getEdgeCount());
        Assertions.assertFalse(g.isTruncated());
        // 无种子时不应触发任何子图构建。
        verify(codeGraphService, never()).buildGraph(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyDouble());
    }

    @Test
    void requirementGraph_shouldResolveSeedsFromFilePathWhenSymbolIdNull() {
        when(permissionService.checkRepoPermission(USER, REPO)).thenReturn(true);
        when(requirementMapper.selectById(10L)).thenReturn(requirement(10, USER, REPO, NOW));
        // 仅有 filePath，symbolId 为空 -> 经 code_file/code_symbol 解析出种子 300。
        when(requirementSymbolMapper.selectList(any())).thenReturn(List.of(
                symbolWithId(1, 10, null, "A.java")));
        CodeFileEntity file = new CodeFileEntity();
        file.setId(55L);
        when(codeFileMapper.selectList(any())).thenReturn(List.of(file));
        CodeSymbolEntity cs = new CodeSymbolEntity();
        cs.setId(300L);
        when(codeSymbolMapper.selectList(any())).thenReturn(List.of(cs));
        when(codeGraphService.buildGraph(eq(USER), eq(REPO), eq(300L), eq("callees"), eq(2), eq(0.0)))
                .thenReturn(graph("300", List.of(node("n1")), List.of(), false));

        CodeGraphVO g = newService().requirementGraph(USER, REPO, 10L);

        Assertions.assertEquals("300", g.getRootId());
        Assertions.assertEquals(1, g.getNodeCount());
        verify(codeGraphService, times(1)).buildGraph(eq(USER), eq(REPO), eq(300L), eq("callees"), eq(2), eq(0.0));
    }

    @Test
    void delete_shouldThrowForbiddenWhenNoRepoPermission() {
        lenient().when(requirementMapper.selectById(eq(10L))).thenReturn(requirement(10, USER, REPO, NOW));
        when(permissionService.checkRepoPermission(USER, REPO)).thenReturn(false);

        Assertions.assertThrows(BizException.class, () -> newService().delete(USER, REPO, 10L));
        verify(requirementMapper, never()).deleteById(any(java.io.Serializable.class));
    }

    // ── B1: external-changes merge window ────────────────────────────────────────

    /**
     * B1 merge path: when a recent external requirement exists within the merge window,
     * summarizeExternal should UPDATE it (title/summary/approach/updatedAt) and REPLACE
     * its requirement_symbol entries with the merged file set — NOT insert a new requirement.
     */
    @Test
    void summarizeExternal_mergeWindow_updatesExistingRequirementInsteadOfInsert() throws IOException {
        Path safeRoot = Files.createTempDirectory("repolens-merge-");
        try {
            when(permissionService.checkRepoPermission(USER, REPO)).thenReturn(true);

            String fileUrl = "file://" + safeRoot.toAbsolutePath();
            RepoEntity repo = new RepoEntity();
            repo.setId(REPO);
            repo.setRepoUrl(fileUrl);
            when(repoMapper.selectById(REPO)).thenReturn(repo);
            when(repoUrlValidator.resolveLocalRepoPath(fileUrl)).thenReturn(safeRoot);

            // Existing external requirement within the 30-minute window.
            RequirementEntity existing = new RequirementEntity();
            existing.setId(99L);
            existing.setUserId(USER);
            existing.setRepoId(REPO);
            existing.setSource("external");
            existing.setTitle("old title");
            existing.setSummary("old summary");
            existing.setStatus("SUMMARIZED");
            existing.setCreatedAt(LocalDateTime.now().minusMinutes(5)); // well within 30-min window
            existing.setUpdatedAt(null); // first burst: updatedAt null → falls back to createdAt

            // requirementMapper.selectList returns the existing external requirement.
            when(requirementMapper.selectList(any())).thenReturn(List.of(existing));

            // Existing symbols: old file A.java
            RequirementSymbolEntity oldSym = new RequirementSymbolEntity();
            oldSym.setId(1L);
            oldSym.setRequirementId(99L);
            oldSym.setFilePath("A.java");
            when(requirementSymbolMapper.selectList(any())).thenReturn(List.of(oldSym));

            // Extractor returns a merged note.
            when(requirementExtractor.extractFromExternalChanges(any(), any()))
                    .thenReturn(Optional.of(new RequirementExtractor.ReqNote(
                            "merged title", "merged summary", "merged approach")));

            // Call with new file B.java (A.java already in existing requirement).
            Optional<RequirementVO> result = newService().summarizeExternal(
                    USER, REPO, List.of("B.java"), null, null);

            // Should return a VO (the updated requirement).
            Assertions.assertTrue(result.isPresent());
            Assertions.assertEquals(99L, result.get().getId());

            // Must NOT have inserted a new requirement.
            verify(requirementMapper, never()).insert(any(RequirementEntity.class));

            // Must have called updateById on the existing requirement.
            ArgumentCaptor<RequirementEntity> updateCaptor = ArgumentCaptor.forClass(RequirementEntity.class);
            verify(requirementMapper, times(1)).updateById(updateCaptor.capture());
            RequirementEntity updated = updateCaptor.getValue();
            Assertions.assertEquals(99L, updated.getId());
            Assertions.assertEquals("merged title", updated.getTitle());
            Assertions.assertNotNull(updated.getUpdatedAt());

            // Old symbols must have been deleted and new merged set re-inserted.
            verify(requirementSymbolMapper, times(1)).delete(any());
            // Both A.java (existing) + B.java (new) = 2 inserts
            verify(requirementSymbolMapper, times(2)).insert(any(RequirementSymbolEntity.class));
        } finally {
            Files.deleteIfExists(safeRoot);
        }
    }

    /**
     * B1 no-merge path: when no external requirement exists (or the most recent is outside
     * the window), summarizeExternal should INSERT a new requirement as before.
     */
    @Test
    void summarizeExternal_noMergeCandidate_insertsNewRequirement() throws IOException {
        Path safeRoot = Files.createTempDirectory("repolens-nomerge-");
        try {
            when(permissionService.checkRepoPermission(USER, REPO)).thenReturn(true);

            String fileUrl = "file://" + safeRoot.toAbsolutePath();
            RepoEntity repo = new RepoEntity();
            repo.setId(REPO);
            repo.setRepoUrl(fileUrl);
            when(repoMapper.selectById(REPO)).thenReturn(repo);
            when(repoUrlValidator.resolveLocalRepoPath(fileUrl)).thenReturn(safeRoot);

            // Existing external requirement but OUTSIDE the 30-minute window.
            RequirementEntity stale = new RequirementEntity();
            stale.setId(55L);
            stale.setUserId(USER);
            stale.setRepoId(REPO);
            stale.setSource("external");
            stale.setTitle("stale");
            stale.setStatus("SUMMARIZED");
            stale.setCreatedAt(LocalDateTime.now().minusMinutes(60)); // outside window
            stale.setUpdatedAt(null);

            when(requirementMapper.selectList(any())).thenReturn(List.of(stale));
            // Note: requirementSymbolMapper.selectList is NOT stubbed — since the stale requirement
            // is outside the merge window, mergeTarget=null and we never load its symbols.

            when(requirementExtractor.extractFromExternalChanges(any(), any()))
                    .thenReturn(Optional.of(new RequirementExtractor.ReqNote(
                            "new req", "new summary", null)));
            when(requirementMapper.insert(any(RequirementEntity.class))).thenAnswer(inv -> {
                Object e = inv.getArgument(0);
                ReflectionTestUtils.setField(e, "id", 200L);
                return 1;
            });

            Optional<RequirementVO> result = newService().summarizeExternal(
                    USER, REPO, List.of("C.java"), null, null);

            Assertions.assertTrue(result.isPresent());
            // Should have inserted a new requirement (not the stale one).
            verify(requirementMapper, times(1)).insert(any(RequirementEntity.class));
            verify(requirementMapper, never()).updateById(any(RequirementEntity.class));
        } finally {
            Files.deleteIfExists(safeRoot);
        }
    }

    /**
     * Security test: a client-supplied {@code realDir="/etc"} must be silently ignored.
     * The server must derive the root from the repo's {@code repoUrl} (a {@code file://} URL
     * pointing to a safe temp dir), so {@code /etc/passwd} is never read.
     */
    @Test
    void summarizeExternal_maliciousRealDir_isIgnoredAndServerDerivedPathUsed() throws IOException {
        Path safeRoot = Files.createTempDirectory("repolens-safe-");
        try {
            when(permissionService.checkRepoPermission(USER, REPO)).thenReturn(true);

            String fileUrl = "file://" + safeRoot.toAbsolutePath();
            RepoEntity repo = new RepoEntity();
            repo.setId(REPO);
            repo.setRepoUrl(fileUrl);
            when(repoMapper.selectById(REPO)).thenReturn(repo);
            when(repoUrlValidator.resolveLocalRepoPath(fileUrl)).thenReturn(safeRoot);
            when(requirementExtractor.extractFromExternalChanges(any(), any()))
                    .thenReturn(Optional.empty());

            // Attacker supplies realDir="/etc", changedFiles=["passwd"]
            Optional<RequirementVO> result = newService().summarizeExternal(
                    USER, REPO, List.of("passwd"), "/etc", null);

            // No requirement produced (passwd does not exist under safeRoot)
            Assertions.assertTrue(result.isEmpty());

            // Server-derived path was used — repoUrlValidator called with the repo's URL, NOT /etc
            verify(repoUrlValidator).resolveLocalRepoPath(fileUrl);

            // Extractor was called but received no /etc/passwd content
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> filesCaptor = ArgumentCaptor.forClass(List.class);
            ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
            verify(requirementExtractor).extractFromExternalChanges(filesCaptor.capture(), contentCaptor.capture());
            // The combined content must NOT include typical /etc/passwd content
            Assertions.assertFalse(contentCaptor.getValue().contains("root:x:0:0"),
                    "Must not have read /etc/passwd content");
        } finally {
            Files.deleteIfExists(safeRoot);
        }
    }

    /**
     * Happy-path security test: for a legitimate file:// repo the server reads files
     * from the server-derived directory, not from any client-supplied path.
     */
    @Test
    void summarizeExternal_fileRepoUrl_readsFilesFromServerDerivedDirectory() throws IOException {
        Path safeRoot = Files.createTempDirectory("repolens-safe-");
        Path testFile = safeRoot.resolve("changes.txt");
        Files.writeString(testFile, "safe-file-content-12345");
        try {
            when(permissionService.checkRepoPermission(USER, REPO)).thenReturn(true);

            String fileUrl = "file://" + safeRoot.toAbsolutePath();
            RepoEntity repo = new RepoEntity();
            repo.setId(REPO);
            repo.setRepoUrl(fileUrl);
            when(repoMapper.selectById(REPO)).thenReturn(repo);
            when(repoUrlValidator.resolveLocalRepoPath(fileUrl)).thenReturn(safeRoot);
            when(requirementExtractor.extractFromExternalChanges(any(), any()))
                    .thenReturn(Optional.empty());

            // Client passes a different realDir — must be ignored
            newService().summarizeExternal(USER, REPO, List.of("changes.txt"), "/some/attacker/path", null);

            // Server-derived path was used
            verify(repoUrlValidator).resolveLocalRepoPath(fileUrl);

            // Extractor received the file content from safeRoot
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> filesCaptor = ArgumentCaptor.forClass(List.class);
            ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
            verify(requirementExtractor).extractFromExternalChanges(filesCaptor.capture(), contentCaptor.capture());
            Assertions.assertTrue(contentCaptor.getValue().contains("safe-file-content-12345"),
                    "Should read file from server-derived dir");
        } finally {
            Files.deleteIfExists(testFile);
            Files.deleteIfExists(safeRoot);
        }
    }
}
