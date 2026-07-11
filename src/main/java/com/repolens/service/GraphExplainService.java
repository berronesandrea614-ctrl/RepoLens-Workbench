package com.repolens.service;

import com.repolens.domain.dto.GraphExplainRequest;

/**
 * 调用图流程解说服务：把一条调用链的节点/边喂给 LLM，生成简洁的中文流程解说。
 */
public interface GraphExplainService {

    /**
     * 生成一条调用链的中文流程解说。
     *
     * <p>失败安全：LLM 异常/超时/配置缺失时返回优雅降级文案，绝不把异常抛给控制器。
     * 仅当用户无该 repo 权限时抛 FORBIDDEN。
     *
     * @param userId 请求用户
     * @param repoId 仓库 id（用于权限校验）
     * @param req    前端从渲染图里抽出的节点/边
     * @return 中文流程解说，或降级文案
     */
    String explain(Long userId, Long repoId, GraphExplainRequest req);
}
