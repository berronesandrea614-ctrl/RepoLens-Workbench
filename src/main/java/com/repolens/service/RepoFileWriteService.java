package com.repolens.service;

import com.repolens.domain.dto.FileWriteRequest;
import com.repolens.domain.vo.FileWriteResultVO;

public interface RepoFileWriteService {
    FileWriteResultVO writeFile(Long userId, FileWriteRequest request);

    /**
     * 在仓库内创建一个【新文件】（目标必须不存在，否则抛异常）。
     * 会自动创建父目录（路径安全校验确保仍在仓库内）。
     */
    FileWriteResultVO createFile(Long userId, FileWriteRequest request);

    /**
     * 从仓库内永久删除一个【已存在的常规文件】。
     * 路径安全校验确保目标仍在仓库根内（防路径穿越 + symlink 逃逸）。
     * 只应由 apply 端点在用户确认后调用，绝不应直接删除 PROPOSED 变更。
     */
    void deleteFile(Long userId, Long repoId, String filePath);
}
