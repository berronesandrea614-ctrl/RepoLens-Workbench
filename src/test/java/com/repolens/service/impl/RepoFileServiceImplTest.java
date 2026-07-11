package com.repolens.service.impl;

import com.repolens.domain.entity.RepoEntity;
import com.repolens.domain.vo.FileTreeNodeVO;
import com.repolens.mapper.RepoMapper;
import com.repolens.security.PermissionService;
import com.repolens.service.support.RepoWorkspaceResolver;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RepoFileServiceImplTest {

    @Test
    void listsTreeSkippingGitAndSortingDirsFirst(@TempDir Path root) throws Exception {
        Path repoDir = Files.createDirectories(root.resolve("7").resolve("main"));
        Files.createDirectories(repoDir.resolve(".git"));
        Files.writeString(repoDir.resolve("README.md"), "hi");
        Path src = Files.createDirectories(repoDir.resolve("src"));
        Files.writeString(src.resolve("A.java"), "class A {}");

        RepoWorkspaceResolver resolver = new RepoWorkspaceResolver(new com.repolens.common.util.RepoUrlValidator());
        ReflectionTestUtils.setField(resolver, "repoStorageRoot", root.toString());

        PermissionService permission = mock(PermissionService.class);
        when(permission.checkRepoPermission(anyLong(), anyLong())).thenReturn(true);

        RepoMapper repoMapper = mock(RepoMapper.class);
        RepoEntity repo = new RepoEntity();
        repo.setId(7L);
        repo.setBranchName("main");
        when(repoMapper.selectById(any())).thenReturn(repo);

        RepoFileServiceImpl service = new RepoFileServiceImpl(permission, repoMapper, resolver);

        FileTreeNodeVO tree = service.listTree(1L, 7L);

        assertThat(tree.isDirectory()).isTrue();
        assertThat(tree.getChildren()).extracting(FileTreeNodeVO::getName)
                .doesNotContain(".git");
        // 目录优先（src 在 README.md 之前）
        assertThat(tree.getChildren().get(0).getName()).isEqualTo("src");
        assertThat(tree.getChildren().get(0).getChildren())
                .extracting(FileTreeNodeVO::getPath)
                .containsExactly("src/A.java");
    }

    @Test
    void symlinkIsExcludedFromTree(@TempDir Path root) throws Exception {
        Path repoDir = Files.createDirectories(root.resolve("7").resolve("main"));
        Files.writeString(repoDir.resolve("README.md"), "hi");
        // Create a file outside the repo to be the symlink target
        Path outsideFile = Files.writeString(root.resolve("outside.txt"), "outside");
        Path symlink = repoDir.resolve("link.txt");
        try {
            Files.createSymbolicLink(symlink, outsideFile);
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "Symlink creation not supported: " + e.getMessage());
            return;
        }

        RepoWorkspaceResolver resolver = new RepoWorkspaceResolver(new com.repolens.common.util.RepoUrlValidator());
        ReflectionTestUtils.setField(resolver, "repoStorageRoot", root.toString());

        PermissionService permission = mock(PermissionService.class);
        when(permission.checkRepoPermission(anyLong(), anyLong())).thenReturn(true);

        RepoMapper repoMapper = mock(RepoMapper.class);
        RepoEntity repo = new RepoEntity();
        repo.setId(7L);
        repo.setBranchName("main");
        when(repoMapper.selectById(any())).thenReturn(repo);

        RepoFileServiceImpl service = new RepoFileServiceImpl(permission, repoMapper, resolver);
        FileTreeNodeVO tree = service.listTree(1L, 7L);

        assertThat(tree.getChildren()).extracting(FileTreeNodeVO::getName)
                .doesNotContain("link.txt");
    }
}
