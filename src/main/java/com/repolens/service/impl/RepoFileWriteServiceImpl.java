package com.repolens.service.impl;

import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.dto.FileWriteRequest;
import com.repolens.domain.entity.RepoEntity;
import com.repolens.domain.vo.FileWriteResultVO;
import com.repolens.mapper.RepoMapper;
import com.repolens.security.PermissionService;
import com.repolens.service.RepoFileWriteService;
import com.repolens.service.support.RepoWorkspaceResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 工作副本文件写入：只允许覆盖仓库内已存在的常规文件（不建新文件/目录），
 * 大小受 repolens.max-file-size-bytes 限制，写入行为打审计日志。
 */
@Slf4j
@Service
public class RepoFileWriteServiceImpl implements RepoFileWriteService {

    private final PermissionService permissionService;
    private final RepoMapper repoMapper;
    private final RepoWorkspaceResolver repoWorkspaceResolver;
    private final long maxFileSizeBytes;

    public RepoFileWriteServiceImpl(PermissionService permissionService,
                                    RepoMapper repoMapper,
                                    RepoWorkspaceResolver repoWorkspaceResolver,
                                    @Value("${repolens.max-file-size-bytes:1048576}") long maxFileSizeBytes) {
        this.permissionService = permissionService;
        this.repoMapper = repoMapper;
        this.repoWorkspaceResolver = repoWorkspaceResolver;
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    @Override
    public FileWriteResultVO writeFile(Long userId, FileWriteRequest request) {
        if (!permissionService.checkRepoPermission(userId, request.getRepoId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "No permission for repo " + request.getRepoId());
        }
        RepoEntity repo = repoMapper.selectById(request.getRepoId());
        if (repo == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "Repo not found");
        }
        byte[] bytes = request.getContent().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxFileSizeBytes) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Content too large: " + bytes.length + " bytes");
        }
        Path repoDir = repoWorkspaceResolver.resolveRepoDirectory(repo);
        Path target = repoWorkspaceResolver.resolveSafeFilePath(repoDir, request.getFilePath());
        if (!Files.isRegularFile(target)) {
            throw new BizException(ErrorCode.NOT_FOUND, "File not found: " + request.getFilePath());
        }
        try {
            Files.write(target, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "Write failed: " + e.getMessage());
        }
        log.info("AUDIT file-write user={} repo={} path={} bytes={}",
                userId, request.getRepoId(), request.getFilePath(), bytes.length);
        return FileWriteResultVO.builder().filePath(request.getFilePath()).bytes(bytes.length).build();
    }

    @Override
    public FileWriteResultVO createFile(Long userId, FileWriteRequest request) {
        if (!permissionService.checkRepoPermission(userId, request.getRepoId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "No permission for repo " + request.getRepoId());
        }
        RepoEntity repo = repoMapper.selectById(request.getRepoId());
        if (repo == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "Repo not found");
        }
        String rawContent = request.getContent() == null ? "" : request.getContent();
        byte[] bytes = rawContent.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxFileSizeBytes) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Content too large: " + bytes.length + " bytes");
        }
        Path repoDir = repoWorkspaceResolver.resolveRepoDirectory(repo);
        Path target = repoWorkspaceResolver.resolveSafeNewFilePath(repoDir, request.getFilePath());
        if (Files.isRegularFile(target)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "File already exists: " + request.getFilePath() + "。请使用 writeFileContent 或 editFileContent 修改现有文件。");
        }
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "Create failed: " + e.getMessage());
        }
        log.info("AUDIT file-create user={} repo={} path={} bytes={}",
                userId, request.getRepoId(), request.getFilePath(), bytes.length);
        return FileWriteResultVO.builder().filePath(request.getFilePath()).bytes(bytes.length).build();
    }

    @Override
    public void deleteFile(Long userId, Long repoId, String filePath) {
        if (!permissionService.checkRepoPermission(userId, repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "No permission for repo " + repoId);
        }
        RepoEntity repo = repoMapper.selectById(repoId);
        if (repo == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "Repo not found");
        }
        Path repoDir = repoWorkspaceResolver.resolveRepoDirectory(repo);
        Path target = repoWorkspaceResolver.resolveSafeFilePath(repoDir, filePath);
        if (!Files.isRegularFile(target)) {
            throw new BizException(ErrorCode.NOT_FOUND, "File not found: " + filePath);
        }
        try {
            Files.delete(target);
        } catch (IOException e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "Delete failed: " + e.getMessage());
        }
        log.info("AUDIT file-delete user={} repo={} path={}", userId, repoId, filePath);
    }
}
