package com.repolens.service.impl;

import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.CodeChunkEntity;
import com.repolens.domain.entity.RepoEntity;
import com.repolens.domain.vo.SearchResultVO;
import com.repolens.mapper.CodeChunkMapper;
import com.repolens.mapper.RepoMapper;
import com.repolens.security.PermissionService;
import com.repolens.service.support.RepoWorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RepoSearchServiceImplTest {

    @TempDir
    Path repoDir;

    private RepoSearchServiceImpl service;
    private PermissionService permission;

    @BeforeEach
    void setup() throws Exception {
        permission = mock(PermissionService.class);
        RepoMapper repoMapper = mock(RepoMapper.class);
        RepoWorkspaceResolver resolver = mock(RepoWorkspaceResolver.class);
        when(permission.checkRepoPermission(anyLong(), anyLong())).thenReturn(true);
        RepoEntity repo = new RepoEntity();
        repo.setId(1L); repo.setBranchName("main");
        when(repoMapper.selectById(eq(1L))).thenReturn(repo);
        when(resolver.resolveRepoDirectory(any())).thenReturn(repoDir);

        Files.createDirectories(repoDir.resolve("src"));
        Files.writeString(repoDir.resolve("src/UserService.java"),
                "public class UserService {\n    public User getUser() {}\n    // getUser helper\n}\n");
        Files.writeString(repoDir.resolve("README.md"), "how to getUser\n");
        Files.createDirectories(repoDir.resolve(".git"));
        Files.writeString(repoDir.resolve(".git/config"), "getUser in git dir\n");
        Files.write(repoDir.resolve("bin.dat"), new byte[]{0x67, 0x65, 0x74, 0x55, 0x73, 0x65, 0x72, 0x00, 0x01});

        CodeChunkMapper chunkMapper = mock(CodeChunkMapper.class);
        when(chunkMapper.selectCount(any())).thenReturn(0L);
        service = new RepoSearchServiceImpl(permission, repoMapper, resolver, chunkMapper);
    }

    @Test
    void findsMatchesAcrossFilesWithLineNumbers() {
        SearchResultVO r = service.search(1L, 1L, "getUser", false, 0, 100);
        assertThat(r.getMatchCount()).isEqualTo(3); // 2 in java + 1 in md, .git 与二进制被跳过
        assertThat(r.getMatches()).extracting("filePath")
                .contains("src/UserService.java", "README.md")
                .doesNotContain(".git/config", "bin.dat");
        assertThat(r.getMatches()).filteredOn(m -> ((com.repolens.domain.vo.SearchMatchVO) m).getLine() == 2)
                .isNotEmpty();
        assertThat(r.isTruncated()).isFalse();
    }

    @Test
    void caseSensitiveFlagRespected() {
        assertThat(service.search(1L, 1L, "getuser", false, 0, 100).getMatchCount()).isEqualTo(3);
        assertThat(service.search(1L, 1L, "getuser", true, 0, 100).getMatchCount()).isZero();
    }

    @Test
    void truncatesAtMaxMatches() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 600; i++) sb.append("hit line\n");
        Files.writeString(repoDir.resolve("big.txt"), sb.toString());
        SearchResultVO r = service.search(1L, 1L, "hit", false, 0, 100);
        assertThat(r.getMatchCount()).isEqualTo(500);
        assertThat(r.isTruncated()).isTrue();
        assertThat(r.getMatches()).hasSize(100);
        assertThat(r.isHasMore()).isTrue();
    }

    @Test
    void rejectsShortQuery() {
        assertThatThrownBy(() -> service.search(1L, 1L, "a", false, 0, 100)).isInstanceOf(BizException.class);
    }

    @Test
    void rejectsWhenNoPermission() {
        when(permission.checkRepoPermission(anyLong(), anyLong())).thenReturn(false);
        assertThatThrownBy(() -> service.search(2L, 1L, "getUser", false, 0, 100)).isInstanceOf(BizException.class);
    }

    @Test
    void skipsNestedGitDirectories() throws Exception {
        Files.createDirectories(repoDir.resolve("sub/.git"));
        Files.writeString(repoDir.resolve("sub/.git/config"), "getUser nested git\n");
        SearchResultVO r = service.search(1L, 1L, "getUser", false, 0, 100);
        assertThat(r.getMatches()).extracting("filePath").doesNotContain("sub/.git/config");
    }

    @Test
    void doesNotFollowSymlinksOutOfRepo() throws Exception {
        java.nio.file.Path outside = java.nio.file.Files.createTempFile("secret", ".txt");
        java.nio.file.Files.writeString(outside, "getUser SECRET LEAK\n");
        java.nio.file.Path link = repoDir.resolve("leak.txt");
        try {
            java.nio.file.Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlinks unsupported here");
        }
        com.repolens.domain.vo.SearchResultVO r = service.search(1L, 1L, "getUser", false, 0, 100);
        assertThat(r.getMatches()).extracting("filePath").doesNotContain("leak.txt");
        assertThat(r.getMatches().stream().noneMatch(m -> m.getLineContent().contains("SECRET LEAK"))).isTrue();
    }

    @Test
    void stripsTrailingCarriageReturnFromCrlfFiles() throws Exception {
        Files.writeString(repoDir.resolve("crlf.txt"), "hello getUser world\r\nsecond\r\n");
        SearchResultVO r = service.search(1L, 1L, "getUser", false, 0, 100);
        com.repolens.domain.vo.SearchMatchVO m = r.getMatches().stream()
                .filter(x -> x.getFilePath().equals("crlf.txt")).findFirst().orElseThrow();
        assertThat(m.getLineContent()).isEqualTo("hello getUser world");
    }

    @Test
    void paginatesResultsCorrectly() throws Exception {
        // Create file with 600 "hit" lines to ensure we hit MAX_MATCHES=500
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 600; i++) sb.append("hit line number " + i + "\n");
        Files.writeString(repoDir.resolve("large.txt"), sb.toString());

        // First page: offset 0, limit 100 should return 100 matches
        SearchResultVO page1 = service.search(1L, 1L, "hit", false, 0, 100);
        assertThat(page1.getMatchCount()).isEqualTo(500); // capped at MAX_MATCHES
        assertThat(page1.getMatches()).hasSize(100);
        assertThat(page1.getOffset()).isZero();
        assertThat(page1.getLimit()).isEqualTo(100);
        assertThat(page1.isHasMore()).isTrue();
        assertThat(page1.isTruncated()).isTrue();

        // Second page: offset 100, limit 100 should return next 100 matches
        SearchResultVO page2 = service.search(1L, 1L, "hit", false, 100, 100);
        assertThat(page2.getMatchCount()).isEqualTo(500);
        assertThat(page2.getMatches()).hasSize(100);
        assertThat(page2.getOffset()).isEqualTo(100);
        assertThat(page2.getLimit()).isEqualTo(100);
        assertThat(page2.isHasMore()).isTrue();

        // Fifth page: offset 400, limit 100 should return final 100 matches (400-499)
        SearchResultVO page5 = service.search(1L, 1L, "hit", false, 400, 100);
        assertThat(page5.getMatches()).hasSize(100);
        assertThat(page5.getOffset()).isEqualTo(400);
        assertThat(page5.isHasMore()).isTrue(); // still truncated

        // Sixth page: offset 500, limit 100 should return empty (beyond cap)
        SearchResultVO page6 = service.search(1L, 1L, "hit", false, 500, 100);
        assertThat(page6.getMatches()).isEmpty();
        assertThat(page6.getOffset()).isEqualTo(500);
        assertThat(page6.isHasMore()).isTrue(); // still truncated even though no matches returned
    }

    @Test
    void smallResultSetHasMoreFalse() throws Exception {
        SearchResultVO r = service.search(1L, 1L, "getUser", false, 0, 100);
        assertThat(r.getMatchCount()).isEqualTo(3);
        assertThat(r.getMatches()).hasSize(3);
        assertThat(r.isHasMore()).isFalse();
    }

    @Test
    void offsetBeyondResultsReturnsEmpty() throws Exception {
        SearchResultVO r = service.search(1L, 1L, "getUser", false, 10, 100);
        assertThat(r.getMatchCount()).isEqualTo(3);
        assertThat(r.getMatches()).isEmpty();
        assertThat(r.isHasMore()).isFalse();
    }

    @Test
    void limitClampedToMax500() throws Exception {
        SearchResultVO r = service.search(1L, 1L, "getUser", false, 0, 1000);
        assertThat(r.getLimit()).isEqualTo(500);
    }

    @Test
    void negativeOffsetClampedToZero() throws Exception {
        SearchResultVO r = service.search(1L, 1L, "getUser", false, -5, 100);
        assertThat(r.getOffset()).isZero();
        assertThat(r.getMatches()).hasSize(3);
    }

    @Test
    void chunkPath_hitsWhenChunksIndexed() throws Exception {
        // Simulate repo with indexed chunks containing the query
        CodeChunkMapper chunkMapper = mock(CodeChunkMapper.class);
        when(chunkMapper.selectCount(any())).thenReturn(1L); // has chunks
        CodeChunkEntity chunk = new CodeChunkEntity();
        chunk.setRepoId(1L);
        chunk.setFilePath("src/main/java/UserService.java");
        chunk.setStartLine(5);
        chunk.setEndLine(20);
        chunk.setContent("public User getUser() {\n    return userRepo.findById(id);\n}");
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk));

        RepoMapper repoMapper2 = mock(RepoMapper.class);
        RepoEntity repo2 = new RepoEntity(); repo2.setId(1L); repo2.setBranchName("main");
        when(repoMapper2.selectById(1L)).thenReturn(repo2);

        RepoSearchServiceImpl svc = new RepoSearchServiceImpl(permission, repoMapper2,
                mock(RepoWorkspaceResolver.class), chunkMapper);

        SearchResultVO r = svc.search(1L, 1L, "getUser", false, 0, 100);
        assertThat(r.getMatchCount()).isGreaterThan(0);
        assertThat(r.getMatches().get(0).getFilePath()).isEqualTo("src/main/java/UserService.java");
        assertThat(r.getMatches().get(0).getLine()).isEqualTo(5); // startLine + line-offset 0
    }

    @Test
    void chunkPath_fallsBackWhenNoChunks() throws Exception {
        // hasIndexedChunks returns false → should fall back to Files.walk
        // The existing @BeforeEach already sets up chunkMapper with selectCount=0
        // Just verify the file-walk path still works (existing test covers this)
        SearchResultVO r = service.search(1L, 1L, "getUser", false, 0, 100);
        assertThat(r.getMatchCount()).isEqualTo(3); // matches from temp dir files
    }

    @Test
    void chunkPath_deduplicatesOverlappingChunks() throws Exception {
        CodeChunkMapper chunkMapper = mock(CodeChunkMapper.class);
        when(chunkMapper.selectCount(any())).thenReturn(2L);

        // Two chunks for the same file with the same startLine=1 → "needle" matches line 1 in both
        CodeChunkEntity chunk1 = new CodeChunkEntity();
        chunk1.setRepoId(1L);
        chunk1.setFilePath("src/main/java/Foo.java");
        chunk1.setStartLine(1);
        chunk1.setEndLine(5);
        chunk1.setContent("needle here\nsecond line\nthird line");

        CodeChunkEntity chunk2 = new CodeChunkEntity();
        chunk2.setRepoId(1L);
        chunk2.setFilePath("src/main/java/Foo.java");
        chunk2.setStartLine(1);  // same startLine → same (filePath, line=1) for "needle"
        chunk2.setEndLine(3);
        chunk2.setContent("needle here\nsecond line");

        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk1, chunk2));

        RepoMapper repoMapper2 = mock(RepoMapper.class);
        RepoEntity repo2 = new RepoEntity(); repo2.setId(1L);
        when(repoMapper2.selectById(1L)).thenReturn(repo2);

        RepoSearchServiceImpl svc = new RepoSearchServiceImpl(permission, repoMapper2,
                mock(RepoWorkspaceResolver.class), chunkMapper);

        SearchResultVO r = svc.search(1L, 1L, "needle", false, 0, 100);
        // "needle" at (Foo.java, line=1) from both chunks → dedup → single result
        assertThat(r.getMatchCount()).isEqualTo(1);
        assertThat(r.getMatches()).hasSize(1);
        assertThat(r.getMatches().get(0).getFilePath()).isEqualTo("src/main/java/Foo.java");
        assertThat(r.getMatches().get(0).getLine()).isEqualTo(1);
    }
}
