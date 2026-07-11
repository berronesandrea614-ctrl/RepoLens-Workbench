package com.repolens.service.support;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repolens.domain.entity.ShadowWorkspaceEntity;
import com.repolens.mapper.ShadowWorkspaceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShadowWorkspaceManagerTest {

    @TempDir
    Path tempDir;

    private ShadowWorkspaceMapper mapper;
    private ShadowWorkspaceManager manager;
    private AtomicLong idCounter;

    @BeforeEach
    void setup() {
        mapper = mock(ShadowWorkspaceMapper.class);
        manager = new ShadowWorkspaceManager(mapper);
        idCounter = new AtomicLong(1);
        ReflectionTestUtils.setField(manager, "shadowRoot", tempDir.resolve("shadow").toString());
        doAnswer(inv -> {
            ShadowWorkspaceEntity e = inv.getArgument(0);
            e.setId(idCounter.getAndIncrement());
            return 1;
        }).when(mapper).insert(any(ShadowWorkspaceEntity.class));
    }

    @Test
    void resolveOrCreate_COPY_strategy_copies_files() throws IOException {
        Path repoDir = tempDir.resolve("repo");
        Files.createDirectories(repoDir.resolve("src"));
        Files.writeString(repoDir.resolve("src/Hello.java"), "class Hello {}");
        Files.createDirectories(repoDir.resolve("node_modules/lodash"));
        Files.writeString(repoDir.resolve("node_modules/lodash/index.js"), "module.exports={}");

        when(mapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        Path shadow = manager.resolveOrCreate(1L, 100L, repoDir);

        assertThat(shadow).isNotNull();
        assertThat(Files.exists(shadow.resolve("src/Hello.java"))).isTrue();
        // 策略可能为 CLONE_COW（macOS APFS 上 cp -c 通常成功），node_modules 不会被跳过
        verify(mapper).insert(any(ShadowWorkspaceEntity.class));
    }

    @Test
    void resolveActive_returns_empty_when_no_active() {
        when(mapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        Optional<Path> result = manager.resolveActive(1L, 100L);
        assertThat(result).isEmpty();
    }

    @Test
    void resolveActive_returns_path_when_active_exists() throws IOException {
        Path shadowPath = tempDir.resolve("shadow/1/100/abc");
        Files.createDirectories(shadowPath);

        ShadowWorkspaceEntity active = new ShadowWorkspaceEntity();
        active.setId(1L);
        active.setRootPath(shadowPath.toString());
        active.setStatus(ShadowWorkspaceEntity.STATUS_ACTIVE);
        when(mapper.selectOne(any(QueryWrapper.class))).thenReturn(active);

        Optional<Path> result = manager.resolveActive(1L, 100L);
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(shadowPath);
    }

    @Test
    void merge_updates_status_to_MERGED() {
        when(mapper.update(any(), any(UpdateWrapper.class))).thenReturn(1);
        manager.merge(1L, 100L);
        verify(mapper).update(any(), any(UpdateWrapper.class));
    }

    @Test
    void discard_updates_status_to_DISCARDED() {
        when(mapper.update(any(), any(UpdateWrapper.class))).thenReturn(1);
        manager.discard(1L, 100L);
        verify(mapper).update(any(), any(UpdateWrapper.class));
    }

    @Test
    void resolveOrCreate_returns_existing_active_shadow() throws IOException {
        Path shadowPath = tempDir.resolve("shadow/1/100/existing");
        Files.createDirectories(shadowPath);

        ShadowWorkspaceEntity active = new ShadowWorkspaceEntity();
        active.setId(1L);
        active.setRootPath(shadowPath.toString());
        active.setStatus(ShadowWorkspaceEntity.STATUS_ACTIVE);
        when(mapper.selectOne(any(QueryWrapper.class))).thenReturn(active);

        Path result = manager.resolveOrCreate(1L, 100L, tempDir.resolve("repo"));
        assertThat(result).isEqualTo(shadowPath);
        verify(mapper, never()).insert(any(ShadowWorkspaceEntity.class));
    }

    @Test
    void COPY_strategy_skips_node_modules_and_target_and_git() throws IOException {
        Path repoDir = tempDir.resolve("repo2");
        Files.createDirectories(repoDir.resolve("src/main/java"));
        Files.writeString(repoDir.resolve("src/main/java/App.java"), "class App {}");
        Files.createDirectories(repoDir.resolve("node_modules/lodash"));
        Files.writeString(repoDir.resolve("node_modules/lodash/index.js"), "var x=1;");
        Files.createDirectories(repoDir.resolve("target/classes"));
        Files.writeString(repoDir.resolve("target/classes/App.class"), "binary");
        Files.createDirectories(repoDir.resolve(".git/objects"));
        Files.writeString(repoDir.resolve(".git/config"), "[core]");
        Files.createDirectories(repoDir.resolve("dist/bundle"));
        Files.writeString(repoDir.resolve("dist/bundle/app.js"), "bundle");

        when(mapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        Path shadow = manager.resolveOrCreate(2L, 200L, repoDir);

        assertThat(shadow).isNotNull();
        assertThat(Files.exists(shadow.resolve("src/main/java/App.java"))).isTrue();
        assertThat(Files.exists(shadow.resolve("node_modules"))).isFalse();
        assertThat(Files.exists(shadow.resolve("target"))).isFalse();
        assertThat(Files.exists(shadow.resolve(".git"))).isFalse();
        assertThat(Files.exists(shadow.resolve("dist"))).isFalse();
    }

    @Test
    void cleanOrphanShadows_removes_expired_shadows() throws IOException {
        ShadowWorkspaceEntity expired = new ShadowWorkspaceEntity();
        expired.setId(1L); expired.setRepoId(1L); expired.setSessionId(100L);
        expired.setRootPath(tempDir.resolve("shadow/1/100/expired").toString());
        expired.setStrategy(ShadowWorkspaceEntity.STRATEGY_COPY);
        expired.setStatus(ShadowWorkspaceEntity.STATUS_ACTIVE);
        expired.setCreatedAt(java.time.LocalDateTime.now().minusHours(48));
        expired.setUpdatedAt(java.time.LocalDateTime.now().minusHours(48));
        Path shadowPath = tempDir.resolve("shadow/1/100/expired");
        Files.createDirectories(shadowPath);

        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(expired));
        when(mapper.updateById(any(ShadowWorkspaceEntity.class))).thenReturn(1);

        manager.cleanOrphanShadows();
        verify(mapper).updateById(any(ShadowWorkspaceEntity.class));
        assertThat(Files.notExists(shadowPath)).isTrue();
    }
}
