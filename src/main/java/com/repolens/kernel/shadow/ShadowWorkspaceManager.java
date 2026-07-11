package com.repolens.kernel.shadow;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repolens.kernel.persistence.entity.RkShadowWorkspaceEntity;
import com.repolens.kernel.persistence.mapper.RkShadowWorkspaceMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 影子工作区生命周期管理：把真仓库 <b>写时复制(CoW)</b> 克隆到隔离副本，
 * agent 在副本里真写盘、真验证，人只审批"是否合并回真目录"。
 *
 * <p>CoW 实现：macOS APFS 的 {@code cp -cR}（clonefile，块共享，近乎零拷贝/零额外磁盘）。
 * 避坑（对照旧版 CLONE_COW 实为全量复制）：只按顶层逐项 clone，排除 VCS/构建产物目录，
 * clone 进程数 = 顶层条目数（十几个），不随文件数爆炸。非 APFS 平台优雅回退为 {@code cp -R}。
 */
@Slf4j
@Service("kernelShadowWorkspaceManager")
public class ShadowWorkspaceManager {

    /** 不进影子区的目录：VCS + 构建产物 + IDE（可在真目录重建，克隆纯浪费）。 */
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "target", "build", "dist", "out", ".gradle",
            "__pycache__", ".pytest_cache", ".idea", ".vscode", ".repolens");

    private final RkShadowWorkspaceMapper shadowMapper;

    /** 影子区根目录，默认 {@code ${user.home}/repolens-workspace/shadows}。 */
    private final Path shadowBase;

    public ShadowWorkspaceManager(RkShadowWorkspaceMapper shadowMapper,
                                  @Value("${repolens.shadow-root:${user.home}/repolens-workspace/shadows}") String shadowRoot) {
        this.shadowMapper = shadowMapper;
        this.shadowBase = Path.of(shadowRoot);
    }

    /** 影子区句柄：DB id + 磁盘根 + 状态。 */
    public record ShadowHandle(Long id, Path root, String status) {}

    /**
     * 取当前 session 的 ACTIVE 影子区；没有（或磁盘已丢）则新建一个 CoW 克隆。
     */
    public ShadowHandle resolveOrCreate(Long repoId, Long sessionId, Long runId, Path repoDir) {
        Optional<ShadowHandle> active = resolveActive(repoId, sessionId);
        if (active.isPresent() && Files.isDirectory(active.get().root())) {
            return active.get();
        }
        return create(repoId, sessionId, runId, repoDir);
    }

    public Optional<ShadowHandle> resolveActive(Long repoId, Long sessionId) {
        RkShadowWorkspaceEntity e = shadowMapper.selectOne(new LambdaQueryWrapper<RkShadowWorkspaceEntity>()
                .eq(RkShadowWorkspaceEntity::getRepoId, repoId)
                .eq(RkShadowWorkspaceEntity::getSessionId, sessionId)
                .eq(RkShadowWorkspaceEntity::getStatus, "ACTIVE")
                .orderByDesc(RkShadowWorkspaceEntity::getId)
                .last("limit 1"));
        return e == null ? Optional.empty()
                : Optional.of(new ShadowHandle(e.getId(), Path.of(e.getRootPath()), e.getStatus()));
    }

    /** 新建影子区：先落库拿 id → 以 id 命名根目录 → CoW 克隆。 */
    public ShadowHandle create(Long repoId, Long sessionId, Long runId, Path repoDir) {
        if (repoDir == null || !Files.isDirectory(repoDir)) {
            throw new IllegalArgumentException("真仓库目录不存在，无法建影子区: " + repoDir);
        }
        RkShadowWorkspaceEntity e = new RkShadowWorkspaceEntity();
        e.setRepoId(repoId);
        e.setSessionId(sessionId);
        e.setRunId(runId);
        e.setRootPath("PENDING");
        e.setBaseCommit(currentCommit(repoDir));
        e.setStrategy(cowSupported() ? "CLONE_COW" : "COPY");
        e.setStatus("ACTIVE");
        shadowMapper.insert(e);

        Path root = shadowBase.resolve(String.valueOf(repoId)).resolve("shadow-" + e.getId());
        try {
            cloneRepo(repoDir, root);
        } catch (Exception ex) {
            e.setStatus("DISCARDED");
            shadowMapper.updateById(e);
            throw new IllegalStateException("影子区 CoW 克隆失败: " + ex.getMessage(), ex);
        }
        e.setRootPath(root.toString());
        shadowMapper.updateById(e);
        log.info("[shadow] 建影子区 #{} repo={} session={} -> {} ({})",
                e.getId(), repoId, sessionId, root, e.getStrategy());
        return new ShadowHandle(e.getId(), root, "ACTIVE");
    }

    /** 按顶层逐项 CoW 克隆，排除 {@link #SKIP_DIRS}。 */
    private void cloneRepo(Path repoDir, Path root) throws IOException, InterruptedException {
        Files.createDirectories(root);
        try (Stream<Path> top = Files.list(repoDir)) {
            for (Path entry : (Iterable<Path>) top::iterator) {
                String name = entry.getFileName().toString();
                if (Files.isDirectory(entry) && SKIP_DIRS.contains(name)) {
                    continue;
                }
                String flag = cowSupported() ? "-cR" : "-R";
                int code = runCli(repoDir, "cp", flag, entry.toString(), root.resolve(name).toString());
                if (code != 0) {
                    throw new IOException("cp " + flag + " 失败(code=" + code + "): " + entry);
                }
            }
        }
    }

    /** 丢弃影子区：删磁盘 + 标 DISCARDED。 */
    public void discard(Long shadowId) {
        RkShadowWorkspaceEntity e = shadowMapper.selectById(shadowId);
        if (e == null) {
            return;
        }
        deleteRecursively(Path.of(e.getRootPath()));
        e.setStatus("DISCARDED");
        shadowMapper.updateById(e);
        log.info("[shadow] 丢弃影子区 #{}", shadowId);
    }

    /** 标记已合并（真正的文件回搬由 {@link FileChangeRecorder#mergeAll} 精确重放，只搬 agent 改过的文件）。 */
    public void markMerged(Long shadowId) {
        RkShadowWorkspaceEntity e = shadowMapper.selectById(shadowId);
        if (e != null) {
            e.setStatus("MERGED");
            shadowMapper.updateById(e);
        }
    }

    public Optional<Path> rootOf(Long shadowId) {
        RkShadowWorkspaceEntity e = shadowMapper.selectById(shadowId);
        return e == null || "PENDING".equals(e.getRootPath())
                ? Optional.empty() : Optional.of(Path.of(e.getRootPath()));
    }

    /** 影子区内的安全路径解析：规范化后必须仍在影子根内，防 {@code ../} 逃逸。 */
    public Path resolveInShadow(Path shadowRoot, String relPath) {
        Path resolved = shadowRoot.resolve(relPath).normalize();
        if (!resolved.startsWith(shadowRoot.normalize())) {
            throw new IllegalArgumentException("路径逃逸影子区: " + relPath);
        }
        return resolved;
    }

    /** 单文件 CoW 复制（合并/回滚时用）。 */
    public void copyFile(Path from, Path to) throws IOException, InterruptedException {
        Files.createDirectories(to.getParent());
        String flag = cowSupported() ? "-c" : "";
        int code = flag.isEmpty()
                ? runCli(null, "cp", from.toString(), to.toString())
                : runCli(null, "cp", flag, from.toString(), to.toString());
        if (code != 0) {
            throw new IOException("单文件 cp 失败(code=" + code + "): " + from);
        }
    }

    // ---- 底层工具 ----

    private boolean cowSupported() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    private String currentCommit(Path repoDir) {
        try {
            Process p = new ProcessBuilder("git", "-C", repoDir.toString(), "rev-parse", "HEAD")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0 && out.matches("[0-9a-f]{7,40}") ? out : null;
        } catch (Exception e) {
            return null;
        }
    }

    private int runCli(Path cwd, String... argv) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
        if (cwd != null) {
            pb.directory(cwd.toFile());
        }
        Process p = pb.start();
        p.getInputStream().readAllBytes();
        if (!p.waitFor(120, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return -1;
        }
        return p.exitValue();
    }

    private void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            log.warn("[shadow] 删除影子区失败 {}: {}", dir, e.getMessage());
        }
    }
}
