package com.repolens.service.impl;

import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.RepoEntity;
import com.repolens.domain.vo.FileTreeNodeVO;
import com.repolens.mapper.RepoMapper;
import com.repolens.security.PermissionService;
import com.repolens.service.RepoFileService;
import com.repolens.service.support.RepoWorkspaceResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 只读文件树列举。仅暴露仓库本地快照，权限 + 路径安全复用统一组件。
 */
@Service
@RequiredArgsConstructor
public class RepoFileServiceImpl implements RepoFileService {

    private static final Set<String> IGNORED = Set.of(".git", "node_modules", "target", ".idea", ".DS_Store");
    private static final int MAX_DEPTH = 12;

    private final PermissionService permissionService;
    private final RepoMapper repoMapper;
    private final RepoWorkspaceResolver repoWorkspaceResolver;

    @Override
    public FileTreeNodeVO listTree(Long userId, Long repoId) {
        if (!permissionService.checkRepoPermission(userId, repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "No permission for repo " + repoId);
        }
        RepoEntity repo = repoMapper.selectById(repoId);
        if (repo == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "Repo not found: " + repoId);
        }
        Path repoDir = repoWorkspaceResolver.resolveReadDirectory(repo);
        return buildNode(repoDir, repoDir, 0);
    }

    private FileTreeNodeVO buildNode(Path root, Path current, int depth) {
        boolean isDir = Files.isDirectory(current);
        String relative = root.equals(current) ? "" : root.relativize(current).toString().replace('\\', '/');
        String name = root.equals(current) ? "" : current.getFileName().toString();

        List<FileTreeNodeVO> children = new ArrayList<>();
        if (isDir && depth < MAX_DEPTH) {
            try (Stream<Path> entries = Files.list(current)) {
                List<Path> sorted = entries
                        .filter(p -> !IGNORED.contains(p.getFileName().toString()))
                        .filter(p -> !Files.isSymbolicLink(p))
                        .sorted(Comparator
                                .comparing((Path p) -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) ? 0 : 1)
                                .thenComparing(p -> p.getFileName().toString().toLowerCase()))
                        .toList();
                for (Path child : sorted) {
                    children.add(buildNode(root, child, depth + 1));
                }
            } catch (IOException e) {
                throw new BizException(ErrorCode.SYSTEM_ERROR, "Failed to list directory: " + e.getMessage());
            }
        }

        return FileTreeNodeVO.builder()
                .name(name)
                .path(relative)
                .directory(isDir)
                .children(isDir ? children : null)
                .build();
    }
}
