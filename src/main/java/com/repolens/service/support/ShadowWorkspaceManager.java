package com.repolens.service.support;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repolens.domain.entity.ShadowWorkspaceEntity;
import com.repolens.mapper.ShadowWorkspaceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class ShadowWorkspaceManager {

    private static final Set<String> SKIP_DIRS =
            Set.of("node_modules", "target", ".git", "dist", ".idea");

    private final ShadowWorkspaceMapper shadowWorkspaceMapper;

    @Value("${repolens.shadow.root:./workspace/shadow}")
    private String shadowRoot;

    @Value("${repolens.shadow.ttl-hours:24}")
    private int ttlHours;

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 3_600_000L)
    public void cleanOrphanShadows() {
        try {
            java.time.LocalDateTime cutoff = java.time.LocalDateTime.now().minusHours(ttlHours);
            var orphans = shadowWorkspaceMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ShadowWorkspaceEntity>()
                            .eq("status", ShadowWorkspaceEntity.STATUS_ACTIVE)
                            .lt("updated_at", cutoff));
            for (ShadowWorkspaceEntity s : orphans) {
                try {
                    Path shadowPath = Paths.get(s.getRootPath());
                    if (Files.exists(shadowPath)) {
                        try (var w = Files.walk(shadowPath)) {
                            w.sorted(java.util.Comparator.reverseOrder())
                             .map(Path::toFile)
                             .forEach(java.io.File::delete);
                        }
                    }
                    s.setStatus(ShadowWorkspaceEntity.STATUS_DISCARDED);
                    shadowWorkspaceMapper.updateById(s);
                    log.info("cleaned orphan shadow id={} path={}", s.getId(), s.getRootPath());
                } catch (Exception e) {
                    log.warn("cleanOrphanShadows: failed for id={}: {}", s.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("cleanOrphanShadows scheduled task failed: {}", e.getMessage());
        }
    }

    public Path resolveOrCreate(Long repoId, Long sessionId, Path repoDir) {
        try {
            ShadowWorkspaceEntity active = findActive(repoId, sessionId);
            if (active != null) {
                Path p = Paths.get(active.getRootPath());
                if (Files.isDirectory(p)) {
                    try {
                        UpdateWrapper<ShadowWorkspaceEntity> uw = new UpdateWrapper<>();
                        uw.eq("id", active.getId()).set("updated_at", LocalDateTime.now());
                        shadowWorkspaceMapper.update(null, uw);
                    } catch (Exception ignored) {}
                    return p;
                }
                discard(repoId, sessionId);
            }
            return createShadow(repoId, sessionId, repoDir);
        } catch (Exception e) {
            log.warn("shadow resolveOrCreate failed repoId={} sessionId={}: {}", repoId, sessionId, e.getMessage());
            return null;
        }
    }

    public Optional<Path> resolveActive(Long repoId, Long sessionId) {
        try {
            ShadowWorkspaceEntity active = findActive(repoId, sessionId);
            if (active == null) return Optional.empty();
            Path p = Paths.get(active.getRootPath());
            return Files.isDirectory(p) ? Optional.of(p) : Optional.empty();
        } catch (Exception e) {
            log.warn("shadow resolveActive failed repoId={} sessionId={}: {}", repoId, sessionId, e.getMessage());
            return Optional.empty();
        }
    }

    public void merge(Long repoId, Long sessionId) {
        try {
            updateStatus(repoId, sessionId, ShadowWorkspaceEntity.STATUS_MERGED);
        } catch (Exception e) {
            log.warn("shadow merge failed repoId={} sessionId={}: {}", repoId, sessionId, e.getMessage());
        }
    }

    public void discard(Long repoId, Long sessionId) {
        try {
            updateStatus(repoId, sessionId, ShadowWorkspaceEntity.STATUS_DISCARDED);
        } catch (Exception e) {
            log.warn("shadow discard failed repoId={} sessionId={}: {}", repoId, sessionId, e.getMessage());
        }
    }

    public static String sha256Hex(byte[] data) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(data);
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) { return ""; }
    }

    private ShadowWorkspaceEntity findActive(Long repoId, Long sessionId) {
        QueryWrapper<ShadowWorkspaceEntity> qw = new QueryWrapper<>();
        qw.eq("repo_id", repoId)
          .eq("session_id", sessionId)
          .eq("status", ShadowWorkspaceEntity.STATUS_ACTIVE)
          .orderByDesc("id")
          .last("LIMIT 1");
        return shadowWorkspaceMapper.selectOne(qw);
    }

    private void updateStatus(Long repoId, Long sessionId, String newStatus) {
        UpdateWrapper<ShadowWorkspaceEntity> uw = new UpdateWrapper<>();
        uw.eq("repo_id", repoId)
          .eq("session_id", sessionId)
          .eq("status", ShadowWorkspaceEntity.STATUS_ACTIVE)
          .set("status", newStatus)
          .set("updated_at", LocalDateTime.now());
        shadowWorkspaceMapper.update(null, uw);
    }

    private Path createShadow(Long repoId, Long sessionId, Path repoDir) throws IOException {
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Path shadowBase = Paths.get(shadowRoot).toAbsolutePath()
                .resolve(String.valueOf(repoId))
                .resolve(String.valueOf(sessionId))
                .resolve(token);
        Files.createDirectories(shadowBase);

        String strategy = ShadowWorkspaceEntity.STRATEGY_COPY;

        if (tryWorktree(repoDir, shadowBase)) {
            strategy = ShadowWorkspaceEntity.STRATEGY_WORKTREE;
        }
        else if (tryCloneCow(repoDir, shadowBase)) {
            strategy = ShadowWorkspaceEntity.STRATEGY_CLONE_COW;
        }
        else {
            copyRecursive(repoDir, shadowBase);
        }

        ShadowWorkspaceEntity entity = new ShadowWorkspaceEntity();
        entity.setRepoId(repoId);
        entity.setSessionId(sessionId);
        entity.setRootPath(shadowBase.toString());
        entity.setStrategy(strategy);
        entity.setStatus(ShadowWorkspaceEntity.STATUS_ACTIVE);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        shadowWorkspaceMapper.insert(entity);

        log.info("shadow created repoId={} sessionId={} strategy={} path={}", repoId, sessionId, strategy, shadowBase);
        return shadowBase;
    }

    private boolean tryWorktree(Path repoDir, Path shadowBase) {
        if (!Files.exists(repoDir.resolve(".git"))) return false;
        try {
            Files.deleteIfExists(shadowBase);
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "worktree", "add", "--detach", shadowBase.toString());
            pb.directory(repoDir.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean done = p.waitFor(30, TimeUnit.SECONDS);
            return done && p.exitValue() == 0 && Files.isDirectory(shadowBase);
        } catch (Exception e) {
            log.debug("shadow worktree failed (will fallback): {}", e.getMessage());
            try { Files.createDirectories(shadowBase); } catch (IOException ignored) {}
            return false;
        }
    }

    private boolean tryCloneCow(Path repoDir, Path shadowBase) {
        try {
            Files.deleteIfExists(shadowBase);
            ProcessBuilder pb = new ProcessBuilder(
                    "cp", "-c", "-R", repoDir.toString(), shadowBase.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean done = p.waitFor(120, TimeUnit.SECONDS);
            return done && p.exitValue() == 0 && Files.isDirectory(shadowBase);
        } catch (Exception e) {
            log.debug("shadow clone-cow failed (will fallback): {}", e.getMessage());
            try { Files.createDirectories(shadowBase); } catch (IOException ignored) {}
            return false;
        }
    }

    private void copyRecursive(Path src, Path dest) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(src)) {
                    String name = dir.getFileName().toString();
                    if (SKIP_DIRS.contains(name)) return FileVisitResult.SKIP_SUBTREE;
                }
                Path target = dest.resolve(src.relativize(dir));
                try {
                    Files.createDirectories(target);
                } catch (IOException e) {
                    log.warn("shadow mkdir failed {}: {}", target, e.getMessage());
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isSymbolicLink()) return FileVisitResult.CONTINUE;
                Path target = dest.resolve(src.relativize(file));
                try {
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    log.warn("shadow copy file failed {}: {}", file, e.getMessage());
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
