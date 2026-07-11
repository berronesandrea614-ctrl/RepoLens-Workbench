package com.repolens.service.support;

import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.RepoEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepoWorkspaceResolverTest {

    private RepoWorkspaceResolver newResolver(Path root) {
        RepoWorkspaceResolver resolver = new RepoWorkspaceResolver(new com.repolens.common.util.RepoUrlValidator());
        ReflectionTestUtils.setField(resolver, "repoStorageRoot", root.toString());
        return resolver;
    }

    private RepoEntity repo(long id, String branch) {
        RepoEntity repo = new RepoEntity();
        repo.setId(id);
        repo.setBranchName(branch);
        return repo;
    }

    @Test
    void resolvesExistingRepoDirectory(@TempDir Path root) throws Exception {
        Path repoDir = Files.createDirectories(root.resolve("7").resolve("main"));
        RepoWorkspaceResolver resolver = newResolver(root);

        Path resolved = resolver.resolveRepoDirectory(repo(7L, "main"));

        assertThat(resolved).isEqualTo(repoDir.toAbsolutePath().normalize());
    }

    @Test
    void throwsWhenRepoDirectoryMissing(@TempDir Path root) {
        RepoWorkspaceResolver resolver = newResolver(root);

        assertThatThrownBy(() -> resolver.resolveRepoDirectory(repo(99L, "main")))
                .isInstanceOf(BizException.class);
    }

    @Test
    void blocksPathTraversal(@TempDir Path root) throws Exception {
        Path repoDir = Files.createDirectories(root.resolve("7").resolve("main"));
        RepoWorkspaceResolver resolver = newResolver(root);

        assertThatThrownBy(() -> resolver.resolveSafeFilePath(repoDir, "../../etc/passwd"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void resolvesSafeRelativePath(@TempDir Path root) throws Exception {
        Path repoDir = Files.createDirectories(root.resolve("7").resolve("main"));
        Files.writeString(repoDir.resolve("A.java"), "class A {}");
        RepoWorkspaceResolver resolver = newResolver(root);

        Path resolved = resolver.resolveSafeFilePath(repoDir, "A.java");

        assertThat(resolved).isEqualTo(repoDir.resolve("A.java").toAbsolutePath().normalize());
    }

    @Test
    void blocksSymlinkEscapingRepoRoot(@TempDir Path root) throws Exception {
        Path repoDir = Files.createDirectories(root.resolve("7").resolve("main"));
        // Create a file OUTSIDE the repo root
        Path outsideFile = Files.writeString(root.resolve("secret.txt"), "secret");
        // Create a symlink INSIDE the repo dir pointing to the outside file
        Path symlink = repoDir.resolve("link.txt");
        try {
            Files.createSymbolicLink(symlink, outsideFile);
        } catch (Exception e) {
            // Filesystem does not support symlinks — skip this test
            Assumptions.assumeTrue(false, "Symlink creation not supported: " + e.getMessage());
            return;
        }
        RepoWorkspaceResolver resolver = newResolver(root);

        assertThatThrownBy(() -> resolver.resolveSafeFilePath(repoDir, "link.txt"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("escapes repository root");
    }

    // ——————————— resolveSafeNewFilePath real-symlink tests ———————————

    /**
     * Attack: repo contains symlink dir `link -> outside_dir`.
     * Path "link/sub/x": immediate parent (link/sub) doesn't exist, but ancestor `link` is a symlink
     * pointing outside. Old code skips the check; new ancestor-walk code catches it.
     */
    @Test
    void resolveSafeNewFilePath_symlinkDirDeepPath_isRejected(@TempDir Path root) throws Exception {
        Path repoDir = Files.createDirectories(root.resolve("7").resolve("main"));
        Path outside = Files.createDirectories(root.resolve("outside"));
        Path symlink = repoDir.resolve("link");
        try {
            Files.createSymbolicLink(symlink, outside);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            Assumptions.assumeTrue(false, "Symlink not supported: " + e.getMessage());
            return;
        }
        RepoWorkspaceResolver resolver = newResolver(root);

        // "link/sub/x": link exists (symlink), link/sub does not exist → ancestor walk finds link → rejected
        assertThatThrownBy(() -> resolver.resolveSafeNewFilePath(repoDir, "link/sub/x"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("escapes repository root");
    }

    /**
     * Attack: repo contains symlink dir `link -> outside_dir`.
     * Path "link/x": immediate parent `link` exists and IS the symlink → also rejected.
     */
    @Test
    void resolveSafeNewFilePath_symlinkDirDirectChild_isRejected(@TempDir Path root) throws Exception {
        Path repoDir = Files.createDirectories(root.resolve("7").resolve("main"));
        Path outside = Files.createDirectories(root.resolve("outside"));
        Path symlink = repoDir.resolve("link");
        try {
            Files.createSymbolicLink(symlink, outside);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            Assumptions.assumeTrue(false, "Symlink not supported: " + e.getMessage());
            return;
        }
        RepoWorkspaceResolver resolver = newResolver(root);

        // "link/x": link exists and is a symlink pointing outside → rejected
        assertThatThrownBy(() -> resolver.resolveSafeNewFilePath(repoDir, "link/x"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("escapes repository root");
    }

    /**
     * Happy path: a legitimate new path with non-existent intermediate directories inside the repo.
     * "newdir/sub/x" where neither newdir nor newdir/sub exists yet → accepted.
     */
    @Test
    void resolveSafeNewFilePath_legitimateNestedPath_isAccepted(@TempDir Path root) throws Exception {
        Path repoDir = Files.createDirectories(root.resolve("7").resolve("main"));
        RepoWorkspaceResolver resolver = newResolver(root);

        // "newdir/sub/x": repoDir exists, newdir and sub don't yet → ancestor walk reaches repoDir → accepted
        Path result = resolver.resolveSafeNewFilePath(repoDir, "newdir/sub/x");

        assertThat(result).isEqualTo(repoDir.resolve("newdir/sub/x").toAbsolutePath().normalize());
    }
}
