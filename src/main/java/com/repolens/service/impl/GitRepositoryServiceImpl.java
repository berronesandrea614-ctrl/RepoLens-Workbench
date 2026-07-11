package com.repolens.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.common.util.RepoUrlValidator;
import com.repolens.domain.entity.CodeFileEntity;
import com.repolens.domain.entity.EgressLogEntity;
import com.repolens.domain.entity.IndexTaskEntity;
import com.repolens.domain.entity.RepoEntity;
import com.repolens.domain.enums.RepoIndexStatus;
import com.repolens.domain.enums.TaskStatus;
import com.repolens.domain.vo.ImportRepoResultVO;
import com.repolens.mapper.CodeFileMapper;
import com.repolens.mapper.IndexTaskMapper;
import com.repolens.mapper.RepoMapper;
import com.repolens.security.PermissionService;
import com.repolens.service.EgressPolicy;
import com.repolens.service.GitRepositoryService;
import com.repolens.service.impl.support.RepositoryFileScanner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.util.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Set;

/**
 * 仓库导入服务实现，对应主链路第一阶段“导入/拉取仓库”。
 * 该实现负责：
 * 1. 校验 repo 与 task 是否匹配；
 * 2. 用 JGit clone 指定分支到本地工作区；
 * 3. 扫描本地文件并回填 code_file；
 * 4. 更新 repo/index_task 状态，给后续 parse 阶段提供事实基础。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GitRepositoryServiceImpl implements GitRepositoryService {

    private static final int CLONE_TIMEOUT_SECONDS = 120;

    private final RepoMapper repoMapper;
    private final IndexTaskMapper indexTaskMapper;
    private final CodeFileMapper codeFileMapper;
    private final PermissionService permissionService;
    private final RepoUrlValidator repoUrlValidator;
    private final PlatformTransactionManager txManager;

    /** 出网策略网关（可选注入）。 */
    @Autowired(required = false)
    private EgressPolicy egressPolicy;

    @Value("${repolens.repo-storage-root:./workspace/repos}")
    private String repoStorageRoot;

    @Value("${repolens.max-file-size-bytes:1048576}")
    private long maxFileSizeBytes;

    @Override
    public ImportRepoResultVO importRepository(Long repoId, Long taskId, Long userId) {
        RepoEntity repo = loadRepoOrThrow(repoId);
        IndexTaskEntity task = loadTaskOrThrow(taskId, repoId);
        if (!permissionService.checkRepoPermission(userId, repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "No repo permission");
        }
        repoUrlValidator.validate(repo.getRepoUrl());

        String branchName = normalizeBranchName(repo.getBranchName());
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            updateTaskStatus(task.getId(), TaskStatus.RUNNING, null);
            updateRepoStatus(repo.getId(), RepoIndexStatus.INDEXING, null);
        });

        try {
            // 每次导入都清理并重建分支目录，确保本地仓库快照与当前任务保持一致。
            Path cloneDir = prepareCloneDirectory(repo.getId(), branchName);
            String latestCommitId = cloneRepository(repo.getRepoUrl(), branchName, cloneDir);
            RepositoryFileScanner scanner = new RepositoryFileScanner(maxFileSizeBytes);
            RepositoryFileScanner.ScanSummary summary = scanner.scan(cloneDir,
                    scannedFile -> {
                        Boolean saved = new TransactionTemplate(txManager).execute(
                                status -> saveOrUpdateCodeFile(repoId, latestCommitId, scannedFile));
                        return Boolean.TRUE.equals(saved);
                    });

            new TransactionTemplate(txManager).executeWithoutResult(status -> {
                updateRepoStatus(repo.getId(), RepoIndexStatus.INDEXED, latestCommitId);
                updateTaskStatus(task.getId(), TaskStatus.SUCCESS, null);
            });

            return ImportRepoResultVO.builder()
                    .repoId(repoId)
                    .taskId(taskId)
                    .latestCommitId(latestCommitId)
                    .scannedFileCount(summary.getScannedFileCount())
                    .savedFileCount(summary.getSavedFileCount())
                    .skippedFileCount(summary.getSkippedFileCount())
                    .status(TaskStatus.SUCCESS)
                    .errorMsg(null)
                    .build();
        } catch (BizException ex) {
            new TransactionTemplate(txManager).executeWithoutResult(s -> markImportFailed(repoId, taskId, ex.getMessage()));
            throw ex;
        } catch (Exception ex) {
            String message = buildImportErrorMessage(ex);
            new TransactionTemplate(txManager).executeWithoutResult(s -> markImportFailed(repoId, taskId, message));
            throw new BizException(ErrorCode.EXTERNAL_SERVICE_ERROR, message);
        }
    }

    private RepoEntity loadRepoOrThrow(Long repoId) {
        RepoEntity repo = repoMapper.selectById(repoId);
        if (repo == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "Repo not found");
        }
        return repo;
    }

    private IndexTaskEntity loadTaskOrThrow(Long taskId, Long repoId) {
        IndexTaskEntity task = indexTaskMapper.selectById(taskId);
        if (task == null || !repoId.equals(task.getRepoId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "Task not found");
        }
        return task;
    }

    private String normalizeBranchName(String branchName) {
        return StringUtils.hasText(branchName) ? branchName.trim() : "main";
    }

    private String sanitizeBranchNameForPath(String branchName) {
        return normalizeBranchName(branchName)
                .replaceAll("[\\\\/:*?\"<>|]+", "_")
                .replaceAll("\\s+", "_");
    }

    /**
     * 为当前 repo/branch 准备本地克隆目录。
     * 这里显式校验路径必须落在 repoStorageRoot 下，避免路径逃逸导致误删其他目录。
     */
    private Path prepareCloneDirectory(Long repoId, String branchName) throws IOException {
        Path rootPath = Paths.get(repoStorageRoot).toAbsolutePath().normalize();
        Files.createDirectories(rootPath);

        String sanitizedBranchName = sanitizeBranchNameForPath(branchName);
        Path clonePath = rootPath
                .resolve(String.valueOf(repoId))
                .resolve(sanitizedBranchName)
                .toAbsolutePath()
                .normalize();
        if (!clonePath.startsWith(rootPath)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Invalid repository clone path");
        }

        if (Files.exists(clonePath)) {
            deleteDirectoryRecursively(clonePath, rootPath);
        }
        Files.createDirectories(clonePath.getParent());
        return clonePath;
    }

    /**
     * 删除旧目录前再次校验目标路径是否仍位于仓库存储根目录内。
     */
    private void deleteDirectoryRecursively(Path targetPath, Path rootPath) throws IOException {
        Path normalizedTarget = targetPath.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(rootPath)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Delete path escapes repository storage root");
        }
        if (!Files.exists(normalizedTarget)) {
            return;
        }

        FileUtils.delete(normalizedTarget.toFile(),
                FileUtils.RECURSIVE | FileUtils.RETRY | FileUtils.SKIP_MISSING);
    }

    /**
     * 本地快照占位 commitId：非 git 目录没有真实 commit，用它让后续 parse/index 阶段能继续工作。
     */
    static final String LOCAL_SNAPSHOT_COMMIT_ID = "local-snapshot";

    private static final Set<String> SNAPSHOT_SKIP_DIRS = Set.of(
            ".git", "node_modules", "target", "dist", "build", ".idea", ".gradle"
    );

    /**
     * 实际执行导入：本地 file:// 目录走「有 .git 则 clone / 无 .git 则复制快照」；
     * 远端 url 保持原有 JGit clone 逻辑不变。
     */
    private String cloneRepository(String repoUrl, String branchName, Path cloneDir) {
        if (isLocalFileUrl(repoUrl)) {
            return importLocalRepository(repoUrl, branchName, cloneDir);
        }
        return cloneRemoteRepository(repoUrl, branchName, cloneDir);
    }

    private boolean isLocalFileUrl(String repoUrl) {
        return repoUrl != null && repoUrl.startsWith("file://");
    }

    /**
     * 本地文件夹导入：
     * - 源目录含 .git → 仍用 JGit clone，但用 sourcePath.toUri() 传入（正确百分号编码，规避非 ASCII 问题）；
     * - 源目录无 .git → 直接复制目录树到 cloneDir，返回占位 commitId，让 parse/index 能在复制文件上继续。
     */
    private String importLocalRepository(String repoUrl, String branchName, Path cloneDir) {
        Path sourcePath = repoUrlValidator.resolveLocalRepoPath(repoUrl);
        if (!Files.isDirectory(sourcePath)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Local repo path is not a directory: " + sourcePath);
        }
        boolean hasGit = Files.isDirectory(sourcePath.resolve(".git"));
        if (hasGit) {
            return cloneRemoteRepository(sourcePath.toUri().toString(), branchName, cloneDir);
        }
        try {
            copyLocalSnapshot(sourcePath, cloneDir);
        } catch (IOException ex) {
            throw new BizException(ErrorCode.EXTERNAL_SERVICE_ERROR, "Copy local snapshot failed: " + ex.getMessage());
        }
        return LOCAL_SNAPSHOT_COMMIT_ID;
    }

    /**
     * 复制本地目录树到目标克隆目录，供无 .git 的普通代码文件夹导入使用。
     * 安全约束：只读取 src 根内的路径、只写入 dst 根内的路径；跳过符号链接、常见构建/依赖目录及超限文件。
     */
    void copyLocalSnapshot(Path src, Path dst) throws IOException {
        Path source = src.toAbsolutePath().normalize();
        Path target = dst.toAbsolutePath().normalize();
        Files.createDirectories(target);
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path normalizedDir = dir.toAbsolutePath().normalize();
                if (!normalizedDir.startsWith(source)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!normalizedDir.equals(source)) {
                    String name = normalizedDir.getFileName() == null ? "" : normalizedDir.getFileName().toString();
                    if (SNAPSHOT_SKIP_DIRS.contains(name) || Files.isSymbolicLink(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                Path resolved = resolveInto(target, source, normalizedDir);
                if (resolved == null) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(resolved);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(file) || !attrs.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                if (attrs.size() > maxFileSizeBytes) {
                    return FileVisitResult.CONTINUE;
                }
                Path normalizedFile = file.toAbsolutePath().normalize();
                Path resolved = resolveInto(target, source, normalizedFile);
                if (resolved == null) {
                    return FileVisitResult.CONTINUE;
                }
                Files.createDirectories(resolved.getParent());
                Files.copy(file, resolved, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 把 source 根下的 path 映射到 target 根下的对应路径，并校验结果仍在 target 内，防止路径逃逸。
     */
    private static Path resolveInto(Path target, Path source, Path path) {
        Path relative = source.relativize(path);
        Path resolved = target.resolve(relative).toAbsolutePath().normalize();
        if (!resolved.startsWith(target)) {
            return null;
        }
        return resolved;
    }

    /**
     * 实际执行 JGit clone，并解析 HEAD 对应的最新 commitId。
     * clone 失败时统一转换成可读的业务异常，避免把底层 Git 异常直接暴露给上层。
     */
    private String cloneRemoteRepository(String repoUrl, String branchName, Path cloneDir) {
        // 出网策略检查（仅对远端 URL，本地 file:// 已在调用方过滤）
        checkEgressGitClone(repoUrl);
        String branchRef = toBranchRef(branchName);
        try (Git git = Git.cloneRepository()
                .setURI(repoUrl)
                .setDirectory(cloneDir.toFile())
                .setBranch(branchRef)
                .setBranchesToClone(List.of(branchRef))
                .setCloneAllBranches(false)
                .setTimeout(CLONE_TIMEOUT_SECONDS)
                .call()) {
            ObjectId head = git.getRepository().resolve("HEAD");
            if (head == null) {
                throw new BizException(ErrorCode.EXTERNAL_SERVICE_ERROR, "Cannot resolve latest commit from cloned repository");
            }
            return head.getName();
        } catch (RefNotFoundException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Branch not found: " + branchName);
        } catch (InvalidRemoteException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Invalid git remote: " + repoUrl);
        } catch (TransportException ex) {
            throw new BizException(ErrorCode.EXTERNAL_SERVICE_ERROR, "Git clone transport failed: " + ex.getMessage());
        } catch (GitAPIException | IOException ex) {
            throw new BizException(ErrorCode.EXTERNAL_SERVICE_ERROR, "Git clone failed: " + ex.getMessage());
        }
    }

    private String toBranchRef(String branchName) {
        String normalized = normalizeBranchName(branchName);
        if (normalized.startsWith("refs/heads/")) {
            return normalized;
        }
        return "refs/heads/" + normalized;
    }

    /**
     * code_file 是后续 parse/chunk/RAG 的事实数据源。
     * 这里按 repoId + filePath 做更新，只有 hash 变化时才真正更新元数据。
     */
    private boolean saveOrUpdateCodeFile(Long repoId,
                                         String latestCommitId,
                                         RepositoryFileScanner.ScannedFile scannedFile) {
        CodeFileEntity existing = codeFileMapper.selectOne(Wrappers.<CodeFileEntity>lambdaQuery()
                .eq(CodeFileEntity::getRepoId, repoId)
                .eq(CodeFileEntity::getFilePath, scannedFile.getRelativePath())
                .last("LIMIT 1"));

        if (existing == null) {
            CodeFileEntity insertEntity = new CodeFileEntity();
            insertEntity.setRepoId(repoId);
            insertEntity.setFilePath(scannedFile.getRelativePath());
            insertEntity.setFileType(scannedFile.getFileType());
            insertEntity.setContentHash(scannedFile.getContentHash());
            insertEntity.setLineCount(scannedFile.getLineCount());
            insertEntity.setLastCommitId(latestCommitId);
            return codeFileMapper.insert(insertEntity) > 0;
        }

        if (scannedFile.getContentHash().equals(existing.getContentHash())) {
            return false;
        }

        existing.setFileType(scannedFile.getFileType());
        existing.setContentHash(scannedFile.getContentHash());
        existing.setLineCount(scannedFile.getLineCount());
        existing.setLastCommitId(latestCommitId);
        return codeFileMapper.updateById(existing) > 0;
    }

    private void updateTaskStatus(Long taskId, TaskStatus status, String errorMsg) {
        IndexTaskEntity updateEntity = new IndexTaskEntity();
        updateEntity.setId(taskId);
        updateEntity.setStatus(status);
        updateEntity.setErrorMsg(errorMsg);
        indexTaskMapper.updateById(updateEntity);
    }

    private void updateRepoStatus(Long repoId, RepoIndexStatus status, String latestCommitId) {
        RepoEntity updateEntity = new RepoEntity();
        updateEntity.setId(repoId);
        updateEntity.setIndexStatus(status);
        if (StringUtils.hasText(latestCommitId)) {
            updateEntity.setLatestCommitId(latestCommitId);
        }
        repoMapper.updateById(updateEntity);
    }

    /**
     * 导入失败时同时回写 repo 与 task 状态，保证 UI 和补偿逻辑看到的是一致结果。
     */
    private void markImportFailed(Long repoId, Long taskId, String errorMsg) {
        String trimmedError = trimError(errorMsg);
        updateRepoStatus(repoId, RepoIndexStatus.FAILED, null);
        updateTaskStatus(taskId, TaskStatus.FAILED, trimmedError);
        log.warn("Repository import failed, repoId={}, taskId={}, error={}", repoId, taskId, trimmedError);
    }

    private String trimError(String errorMsg) {
        if (!StringUtils.hasText(errorMsg)) {
            return "Unknown import error";
        }
        String trimmed = errorMsg.trim();
        return trimmed.length() > 2000 ? trimmed.substring(0, 2000) : trimmed;
    }

    private String buildImportErrorMessage(Exception ex) {
        String message = ex.getMessage();
        if (!StringUtils.hasText(message)) {
            return "Repository import failed";
        }
        return message.trim();
    }

    /**
     * 出网策略检查（GIT_CLONE 路径）。
     * LOCAL_ONLY 模式下远端 git clone 被阻断（抛出 BizException，上层展示为导入失败）。
     * 本地 file:// URL 不调此方法（在 isLocalFileUrl 处已过滤）。
     */
    private void checkEgressGitClone(String repoUrl) {
        if (egressPolicy == null || !StringUtils.hasText(repoUrl)) {
            return;
        }
        try {
            java.net.URI uri = java.net.URI.create(repoUrl);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return; // 非标准 URL 格式，跳过检查
            }
            int port = uri.getPort();
            if (port <= 0) {
                port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : ("http".equalsIgnoreCase(uri.getScheme()) ? 80 : 22);
            }
            egressPolicy.checkAndLog(host, port, EgressLogEntity.PURPOSE_GIT_CLONE, null);
        } catch (com.repolens.common.exception.BizException blocked) {
            throw blocked;
        } catch (Exception unexpected) {
            log.warn("EgressPolicy check failed in GitRepositoryServiceImpl (fail-safe), err={}", unexpected.getMessage());
        }
    }
}
