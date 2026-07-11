package com.repolens.domain.dto;

import lombok.Data;

import java.util.List;

/**
 * 调用图流程解说请求。
 * 前端从已渲染的调用图里抽出精简的节点/边标签，交给后端喂给 LLM 生成中文流程解说。
 *
 * <p>约定：
 * <ul>
 *   <li>rootLabel：起点节点的短标签，例如 "UserController.getUser [Controller]"。</li>
 *   <li>nodes：节点短标签列表，例如 "UserService.getUser [Service]"。</li>
 *   <li>edges：调用边，例如 "UserController.getUser -> UserService.getUser"。</li>
 * </ul>
 */
@Data
public class GraphExplainRequest {

    private String rootLabel;
    private List<String> nodes;
    private List<String> edges;
}
