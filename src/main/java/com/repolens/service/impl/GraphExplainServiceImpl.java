package com.repolens.service.impl;

import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.dto.GraphExplainRequest;
import com.repolens.llm.LlmClient;
import com.repolens.llm.config.LlmRuntimeConfig;
import com.repolens.llm.model.LlmRequest;
import com.repolens.llm.model.LlmResponse;
import com.repolens.security.PermissionService;
import com.repolens.service.GraphExplainService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 调用图流程解说实现：单发 LLM 调用（无 tools / 无 messages），把调用链讲成一段中文流程。
 *
 * <p>设计要点：
 * <ol>
 *   <li>先做 repo 权限校验，无权限抛 FORBIDDEN；</li>
 *   <li>低温度单轮生成，追求稳定；</li>
 *   <li>失败安全——任何 LLM 异常都吞掉并返回降级文案，绝不冒泡到控制器；</li>
 *   <li>对输入节点/边数量与输出长度做上限保护，避免 prompt 过长或返回超长文本。</li>
 * </ol>
 */
@Slf4j
@Service
public class GraphExplainServiceImpl implements GraphExplainService {

    /** 输入节点/边各自的条数上限，防止 prompt 过长。 */
    private static final int MAX_ITEMS = 80;
    /** 输出解说的长度上限。 */
    private static final int MAX_OUTPUT_CHARS = 1200;
    /** LLM 不可用时的降级文案。 */
    private static final String FALLBACK = "（暂无法生成流程解说，请稍后重试或检查模型配置）";

    private static final String SYSTEM_PROMPT = """
            你是代码流程讲解员。根据给定的调用链节点和边，用简洁的中文分点讲清这条数据流/调用链在做什么\
            （请求从哪进、经过哪些层、最终做了什么）。不要逐条复述节点，要讲流程含义。""";

    private final PermissionService permissionService;
    private final LlmClient llmClient;
    private final LlmRuntimeConfig llmRuntimeConfig;

    public GraphExplainServiceImpl(PermissionService permissionService, LlmClient llmClient,
                                   LlmRuntimeConfig llmRuntimeConfig) {
        this.permissionService = permissionService;
        this.llmClient = llmClient;
        this.llmRuntimeConfig = llmRuntimeConfig;
    }

    @Override
    public String explain(Long userId, Long repoId, GraphExplainRequest req) {
        if (!permissionService.checkRepoPermission(userId, repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "No permission for repo " + repoId);
        }
        try {
            String userPrompt = buildUserPrompt(req);
            LlmResponse response = llmClient.generate(LlmRequest.builder()
                    .modelName(llmRuntimeConfig.getModelName())
                    .systemPrompt(SYSTEM_PROMPT)
                    .userPrompt(userPrompt)
                    .temperature(0.2d)
                    .timeoutMs(llmRuntimeConfig.getTimeoutMs())
                    .build());
            if (response == null || !StringUtils.hasText(response.getContent())) {
                return FALLBACK;
            }
            String content = response.getContent().trim();
            if (content.length() > MAX_OUTPUT_CHARS) {
                content = content.substring(0, MAX_OUTPUT_CHARS);
            }
            return content;
        } catch (Exception ex) {
            // 流程解说是锦上添花的能力：任何失败都降级为友好文案，绝不拖垮请求。
            log.warn("graph explain failed, fallback. repoId={}, err={}", repoId, ex.getMessage());
            return FALLBACK;
        }
    }

    /**
     * 把 rootLabel + 节点 + 边拼成给模型的 user prompt。对条数做上限截断。
     */
    private String buildUserPrompt(GraphExplainRequest req) {
        StringBuilder sb = new StringBuilder();
        if (req != null && StringUtils.hasText(req.getRootLabel())) {
            sb.append("起点节点:\n").append(req.getRootLabel().trim()).append("\n\n");
        }
        appendSection(sb, "节点列表:", req == null ? null : req.getNodes());
        appendSection(sb, "调用边:", req == null ? null : req.getEdges());
        return sb.toString();
    }

    private void appendSection(StringBuilder sb, String title, List<String> items) {
        sb.append(title).append('\n');
        if (items == null || items.isEmpty()) {
            sb.append("（无）\n\n");
            return;
        }
        int limit = Math.min(items.size(), MAX_ITEMS);
        for (int i = 0; i < limit; i++) {
            String item = items.get(i);
            if (StringUtils.hasText(item)) {
                sb.append("- ").append(item.trim()).append('\n');
            }
        }
        if (items.size() > MAX_ITEMS) {
            sb.append("- …（其余 ").append(items.size() - MAX_ITEMS).append(" 条已省略）\n");
        }
        sb.append('\n');
    }
}
