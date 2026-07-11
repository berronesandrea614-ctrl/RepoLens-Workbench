package com.repolens.service.support;

import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.common.util.RepoUrlValidator;
import com.repolens.domain.entity.RepoEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 仓库本地快照目录解析 + 路径安全。
 * 从 ReadonlyToolServiceImpl 抽取，供 getFileContent 与文件树等只读能力复用。
 */
@Component
public class RepoWorkspaceResolver {

    @Value("${repolens.repo-storage-root:./workspace/repos}")
    private String repoStorageRoot;

    private final RepoUrlValidator repoUrlValidator;

    public RepoWorkspaceResolver(RepoUrlValidator repoUrlValidator) {
        this.repoUrlValidator = repoUrlValidator;
    }

    /**
     * 「只读/查看」路径专用的目录解析：本地 {@code file://} 仓库优先返回<b>真实项目目录</b>，
     * 让外部工具/飞书对真实项目的改动在 app 里即时可见，消除「快照 vs 真实目录」漂移
     * （无需重新索引才看到文件改动）。真实目录不存在或非本地仓库时，回退到
     * {@link #resolveRepoDirectory} 的快照目录。
     *
     * <p>仅用于文件内容/文件树/搜索等只读展示；索引/解析仍走快照目录（符号图需重索引才更新）。
     */
    public Path resolveReadDirectory(RepoEntity repo) {
        String url = repo.getRepoUrl();
        if (url != null && url.startsWith("file://")) {
            try {
                Path real = repoUrlValidator.resolveLocalRepoPath(url);
                if (Files.isDirectory(real)) {
                    return real;
                }
            } catch (Exception ignore) {
                // 解析失败/目录不存在 → 回退快照
            }
        }
        return resolveRepoDirectory(repo);
    }

    public Path resolveRepoDirectory(RepoEntity repo) {
        String branchName = StringUtils.hasText(repo.getBranchName()) ? repo.getBranchName().trim() : "main";
        String sanitizedBranch = sanitizeBranchNameForPath(branchName);
        Path rootPath = Paths.get(repoStorageRoot).toAbsolutePath().normalize();
        Path repoPath = rootPath.resolve(String.valueOf(repo.getId()))
                .resolve(sanitizedBranch)
                .toAbsolutePath()
                .normalize();
        if (!repoPath.startsWith(rootPath)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Invalid repository local path");
        }
        if (!Files.exists(repoPath) || !Files.isDirectory(repoPath)) {
            throw new BizException(ErrorCode.NOT_FOUND, "Local repository not found, import repository first");
        }
        return repoPath;
    }

    public Path resolveSafeFilePath(Path repoDirectory, String relativePath) {
        Path resolvedPath = repoDirectory.resolve(relativePath).toAbsolutePath().normalize();
        if (!resolvedPath.startsWith(repoDirectory)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "File path escapes repository root");
        }
        // Defeat symlink escape: if the target exists, its real (symlink-resolved) path
        // must still be inside the repo's real directory.
        if (Files.exists(resolvedPath)) {
            try {
                Path realRepoDir = repoDirectory.toRealPath();
                Path realTarget = resolvedPath.toRealPath();
                if (!realTarget.startsWith(realRepoDir)) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "File path escapes repository root");
                }
            } catch (java.io.IOException e) {
                throw new BizException(ErrorCode.BAD_REQUEST, "Unable to resolve real file path");
            }
        }
        return resolvedPath;
    }

    /**
     * 为【新文件】（不要求已存在）解析安全目标路径。
     * 防路径穿越：确保规范化路径在 repoDirectory 内。
     * 防 symlink 逃逸：从目标父路径向上寻找最近的已存在祖先，对该祖先执行 toRealPath()
     * 并要求其仍在仓库真实路径内。
     * 仅检查直接父级在 Files.exists() 后 skips the check 的漏洞：当仓库中存在 symlink
     * evil→/etc 且 filePath="evil/sub/x" 时，evil/sub 不存在，但 evil 存在且指向仓库外。
     * 走祖先法可捕获该情形。
     */
    public Path resolveSafeNewFilePath(Path repoDirectory, String relativePath) {
        Path resolved = repoDirectory.resolve(relativePath).toAbsolutePath().normalize();
        // 词法路径穿越快速检查。
        if (!resolved.startsWith(repoDirectory)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "File path escapes repository root");
        }
        // Final-component symlink check: if the target itself already exists as a symlink,
        // reject — writing through it would follow the link and could land outside the repo.
        if (Files.isSymbolicLink(resolved)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Target path is a symlink — refusing to write");
        }
        // Symlink 逃逸检查：向上遍历找到最近已存在的祖先，验证其真实路径仍在仓库内。
        try {
            Path realRepoDir = repoDirectory.toRealPath();
            Path ancestor = resolved.getParent();
            while (ancestor != null && !Files.exists(ancestor)) {
                ancestor = ancestor.getParent();
            }
            if (ancestor != null) {
                Path realAncestor = ancestor.toRealPath();
                if (!realAncestor.startsWith(realRepoDir)) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "File path escapes repository root");
                }
            }
        } catch (BizException ex) {
            throw ex;
        } catch (java.io.IOException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Unable to resolve real parent path");
        }
        return resolved;
    }

    private String sanitizeBranchNameForPath(String branchName) {
        return branchName
                .replaceAll("[\\\\/:*?\"<>|]+", "_")
                .replaceAll("\\s+", "_");
    }
}
