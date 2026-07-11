package com.repolens.service.impl;

import com.repolens.common.exception.BizException;
import com.repolens.domain.dto.FileWriteRequest;
import com.repolens.domain.entity.RepoEntity;
import com.repolens.domain.vo.FileWriteResultVO;
import com.repolens.mapper.RepoMapper;
import com.repolens.security.PermissionService;
import com.repolens.service.support.RepoWorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RepoFileWriteServiceImplTest {

    @TempDir
    Path repoDir;

    private PermissionService permission;
    private RepoMapper repoMapper;
    private RepoWorkspaceResolver resolver;
    private RepoFileWriteServiceImpl service;

    private FileWriteRequest req(long repoId, String path, String content) {
        FileWriteRequest r = new FileWriteRequest();
        r.setRepoId(repoId); r.setFilePath(path); r.setContent(content);
        return r;
    }

    @BeforeEach
    void setup() throws Exception {
        permission = mock(PermissionService.class);
        repoMapper = mock(RepoMapper.class);
        resolver = mock(RepoWorkspaceResolver.class);
        when(permission.checkRepoPermission(anyLong(), anyLong())).thenReturn(true);
        RepoEntity repo = new RepoEntity();
        repo.setId(1L); repo.setBranchName("main");
        when(repoMapper.selectById(eq(1L))).thenReturn(repo);
        when(resolver.resolveRepoDirectory(any())).thenReturn(repoDir);
        // 真实 resolveSafeFilePath 语义：拼接 + 校验在 repoDir 内
        when(resolver.resolveSafeFilePath(any(), any())).thenAnswer(inv -> {
            Path dir = inv.getArgument(0);
            String rel = inv.getArgument(1);
            Path p = dir.resolve(rel).toAbsolutePath().normalize();
            if (!p.startsWith(dir)) throw new BizException(com.repolens.common.constants.ErrorCode.BAD_REQUEST, "escape");
            return p;
        });
        Files.createDirectories(repoDir.resolve("src"));
        Files.writeString(repoDir.resolve("src/A.java"), "old");
        service = new RepoFileWriteServiceImpl(permission, repoMapper, resolver, 1048576);
    }

    @Test
    void writesExistingFileAndReturnsBytes() throws Exception {
        FileWriteResultVO vo = service.writeFile(1L, req(1, "src/A.java", "new content"));
        assertThat(vo.getBytes()).isEqualTo("new content".getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertThat(Files.readString(repoDir.resolve("src/A.java"))).isEqualTo("new content");
    }

    @Test
    void rejectsWhenNoPermission() {
        when(permission.checkRepoPermission(anyLong(), anyLong())).thenReturn(false);
        assertThatThrownBy(() -> service.writeFile(2L, req(1, "src/A.java", "x")))
                .isInstanceOf(BizException.class);
    }

    @Test
    void rejectsNonexistentFile() {
        assertThatThrownBy(() -> service.writeFile(1L, req(1, "src/Nope.java", "x")))
                .isInstanceOf(BizException.class).hasMessageContaining("not found");
    }

    @Test
    void rejectsEscapePath() {
        assertThatThrownBy(() -> service.writeFile(1L, req(1, "../../etc/passwd", "x")))
                .isInstanceOf(BizException.class);
    }

    @Test
    void rejectsOversizeContent() {
        RepoFileWriteServiceImpl small = new RepoFileWriteServiceImpl(permission, repoMapper, resolver, 5);
        assertThatThrownBy(() -> small.writeFile(1L, req(1, "src/A.java", "123456")))
                .isInstanceOf(BizException.class).hasMessageContaining("too large");
    }

    @Test
    void rejectsUnknownRepo() {
        when(repoMapper.selectById(eq(9L))).thenReturn(null);
        assertThatThrownBy(() -> service.writeFile(1L, req(9, "src/A.java", "x")))
                .isInstanceOf(BizException.class);
    }

    @Test
    void doesNotCreateFileDeletedAfterCheckWindow() throws Exception {
        // WRITE without CREATE: writing to a nonexistent path must throw, not create
        Files.delete(repoDir.resolve("src/A.java"));
        assertThatThrownBy(() -> service.writeFile(1L, req(1, "src/A.java", "x")))
                .isInstanceOf(BizException.class);
    }
}
