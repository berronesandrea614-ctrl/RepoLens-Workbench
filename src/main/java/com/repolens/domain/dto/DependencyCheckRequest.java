package com.repolens.domain.dto;

import lombok.Data;

import java.util.List;

/**
 * 依赖体检请求体（POST /api/repos/{repoId}/dependency-check）。
 */
@Data
public class DependencyCheckRequest {

    /**
     * 要体检的 file_change_log.id 列表。
     * 与 sessionId 二选一；同时传时 changeIds 优先。
     */
    private List<Long> changeIds;

    /**
     * 按会话 id 拉全部变更进行体检（当 changeIds 为空时生效）。
     */
    private Long sessionId;
}
