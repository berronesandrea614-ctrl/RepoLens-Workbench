package com.repolens.service.support;

import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.RepoEntity;
import com.repolens.mapper.RepoMapper;
import com.repolens.service.impl.support.AgentRulesLoader;
import com.repolens.service.support.RepoWorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

// setUp() stubs for repoId=42 are reused by most tests, but a few tests (e.g. repoNotFound)
// use a different repoId, making some setUp stubs unnecessary for those tests.
// LENIENT mode avoids UnnecessaryStubbingException while keeping shared setup concise.
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class AgentRulesLoaderTest {

    @TempDir
    Path tempDir;

    @Mock
    private RepoMapper repoMapper;

    @Mock
    private RepoWorkspaceResolver repoWorkspaceResolver;

    private AgentRulesLoader loader;

    private RepoEntity repo;

    @BeforeEach
    void setUp() {
        loader = new AgentRulesLoader(repoMapper, repoWorkspaceResolver);

        repo = new RepoEntity();
        repo.setId(42L);
        repo.setBranchName("main");
        repo.setRepoName("test-repo");
        when(repoMapper.selectById(eq(42L))).thenReturn(repo);
        when(repoWorkspaceResolver.resolveRepoDirectory(repo)).thenReturn(tempDir);
    }

    // -----------------------------------------------------------------------
    // AGENTS.md happy path
    // -----------------------------------------------------------------------

    @Test
    void loadRules_agentsMdExists_returnsContent() throws IOException {
        Path agentsMd = tempDir.resolve("AGENTS.md");
        Files.writeString(agentsMd, "# Rules\n- Always write tests\n", StandardCharsets.UTF_8);

        // Loader stops at AGENTS.md; second candidate is never reached.
        when(repoWorkspaceResolver.resolveSafeFilePath(tempDir, "AGENTS.md")).thenReturn(agentsMd);

        String result = loader.loadRules(42L);

        assertThat(result).isNotNull();
        assertThat(result).contains("Always write tests");
    }

    // -----------------------------------------------------------------------
    // .repolens/rules.md fallback
    // -----------------------------------------------------------------------

    @Test
    void loadRules_noAgentsMd_fallsBackToRepolensRules() throws IOException {
        Path rulesDir = tempDir.resolve(".repolens");
        Files.createDirectories(rulesDir);
        Path rulesMd = rulesDir.resolve("rules.md");
        Files.writeString(rulesMd, "# Project Rules\n- Use Java 17\n", StandardCharsets.UTF_8);

        // AGENTS.md not present
        when(repoWorkspaceResolver.resolveSafeFilePath(tempDir, "AGENTS.md"))
                .thenReturn(tempDir.resolve("AGENTS.md")); // non-existent
        when(repoWorkspaceResolver.resolveSafeFilePath(tempDir, ".repolens/rules.md")).thenReturn(rulesMd);

        String result = loader.loadRules(42L);

        assertThat(result).isNotNull();
        assertThat(result).contains("Use Java 17");
    }

    // -----------------------------------------------------------------------
    // AGENTS.md preferred over .repolens/rules.md when both exist
    // -----------------------------------------------------------------------

    @Test
    void loadRules_bothExist_agentsMdHasPriority() throws IOException {
        Path agentsMd = tempDir.resolve("AGENTS.md");
        Files.writeString(agentsMd, "agents rules content", StandardCharsets.UTF_8);
        Path rulesDir = tempDir.resolve(".repolens");
        Files.createDirectories(rulesDir);
        Path rulesMd = rulesDir.resolve("rules.md");
        Files.writeString(rulesMd, "repolens rules content", StandardCharsets.UTF_8);

        when(repoWorkspaceResolver.resolveSafeFilePath(tempDir, "AGENTS.md")).thenReturn(agentsMd);
        // second candidate not reached because AGENTS.md is found first
        // (no stub needed; loader won't call it)

        String result = loader.loadRules(42L);

        assertThat(result).contains("agents rules content");
        assertThat(result).doesNotContain("repolens rules content");
    }

    // -----------------------------------------------------------------------
    // Neither file exists
    // -----------------------------------------------------------------------

    @Test
    void loadRules_noFileExists_returnsNull() {
        when(repoWorkspaceResolver.resolveSafeFilePath(tempDir, "AGENTS.md"))
                .thenReturn(tempDir.resolve("AGENTS.md")); // doesn't exist
        when(repoWorkspaceResolver.resolveSafeFilePath(tempDir, ".repolens/rules.md"))
                .thenReturn(tempDir.resolve(".repolens/rules.md")); // doesn't exist

        String result = loader.loadRules(42L);
        assertThat(result).isNull();
    }

    // -----------------------------------------------------------------------
    // Repo not found
    // -----------------------------------------------------------------------

    @Test
    void loadRules_repoNotFound_returnsNull() {
        when(repoMapper.selectById(eq(99L))).thenReturn(null);

        String result = loader.loadRules(99L);
        assertThat(result).isNull();
    }

    // -----------------------------------------------------------------------
    // Truncation
    // -----------------------------------------------------------------------

    @Test
    void loadRules_contentExceeds4000_isTruncated() throws IOException {
        Path agentsMd = tempDir.resolve("AGENTS.md");
        String bigContent = "z".repeat(5000);
        Files.writeString(agentsMd, bigContent, StandardCharsets.UTF_8);

        when(repoWorkspaceResolver.resolveSafeFilePath(tempDir, "AGENTS.md")).thenReturn(agentsMd);

        String result = loader.loadRules(42L);

        assertThat(result).isNotNull();
        assertThat(result).endsWith("[...已截断]");
        // Content up to 4000 chars + truncation marker
        assertThat(result).startsWith("z".repeat(100)); // spot-check beginning
    }

    // -----------------------------------------------------------------------
    // Failure safety: exception in resolveRepoDirectory
    // -----------------------------------------------------------------------

    @Test
    void loadRules_resolveRepoDirectoryThrows_returnsNull() {
        when(repoWorkspaceResolver.resolveRepoDirectory(repo))
                .thenThrow(new BizException(com.repolens.common.constants.ErrorCode.NOT_FOUND,
                        "Local repository not found"));

        String result = loader.loadRules(42L);
        assertThat(result).isNull();
    }

    // -----------------------------------------------------------------------
    // Failure safety: safe path resolution throws (symlink escape)
    // -----------------------------------------------------------------------

    @Test
    void loadRules_safePathThrows_skipsAndReturnsNull() {
        when(repoWorkspaceResolver.resolveSafeFilePath(tempDir, "AGENTS.md"))
                .thenThrow(new BizException(com.repolens.common.constants.ErrorCode.BAD_REQUEST,
                        "File path escapes repository root"));
        when(repoWorkspaceResolver.resolveSafeFilePath(tempDir, ".repolens/rules.md"))
                .thenThrow(new BizException(com.repolens.common.constants.ErrorCode.BAD_REQUEST,
                        "File path escapes repository root"));

        String result = loader.loadRules(42L);
        assertThat(result).isNull();
    }

    // -----------------------------------------------------------------------
    // Empty file: treated as not found
    // -----------------------------------------------------------------------

    @Test
    void loadRules_emptyFile_skipsAndReturnsNull() throws IOException {
        Path agentsMd = tempDir.resolve("AGENTS.md");
        Files.writeString(agentsMd, "   \n", StandardCharsets.UTF_8); // whitespace only

        when(repoWorkspaceResolver.resolveSafeFilePath(tempDir, "AGENTS.md")).thenReturn(agentsMd);
        when(repoWorkspaceResolver.resolveSafeFilePath(tempDir, ".repolens/rules.md"))
                .thenReturn(tempDir.resolve(".repolens/rules.md")); // non-existent

        String result = loader.loadRules(42L);
        assertThat(result).isNull();
    }
}
