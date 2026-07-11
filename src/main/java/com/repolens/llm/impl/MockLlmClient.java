package com.repolens.llm.impl;

import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.llm.LlmClient;
import com.repolens.llm.LlmClientException;
import com.repolens.llm.config.LlmRuntimeConfig;
import com.repolens.llm.model.LlmMessage;
import com.repolens.llm.model.LlmRequest;
import com.repolens.llm.model.LlmResponse;
import com.repolens.llm.model.ToolCall;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Mock LLM 客户端。
 * 作用是让 RepoLens 在没有真实 API Key 的环境里也能完整演示：
 * RAG 检索、引用组装、问答落库、降级逻辑都可以离线验证。
 */
@Component
public class MockLlmClient implements LlmClient {

    private static final String NO_EVIDENCE_ANSWER = "当前仓库中没有检索到足够证据，无法可靠回答。";

    private final LlmRuntimeConfig llmRuntimeConfig;

    /** mock 强制失败开关：仅用于测试触发降级路径，保持 @Value（不进运行时配置）。 */
    @Value("${repolens.llm.mock-force-fail:false}")
    private boolean mockForceFail;

    public MockLlmClient(LlmRuntimeConfig llmRuntimeConfig) {
        this.llmRuntimeConfig = llmRuntimeConfig;
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        long start = System.currentTimeMillis();
        if (mockForceFail) {
            throw new LlmClientException("LLM_CALL_FAILED", "Mock LLM forced failure");
        }
        if (request == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "LLM request is empty");
        }

        // agentic 模式：带 tools 时，先要求调用一次检索工具，拿到工具结果后再给最终答案。
        // 这样无需真实模型即可离线验证多步 loop / 工具回填 / 终止条件。
        if (request.getTools() != null && !request.getTools().isEmpty()) {
            return generateAgentic(request, start);
        }

        String userPrompt = request.getUserPrompt() == null ? "" : request.getUserPrompt();
        List<EvidenceRef> refs = parseEvidenceRefs(userPrompt);
        String model = StringUtils.hasText(request.getModelName()) ? request.getModelName() : llmRuntimeConfig.getModelName();

        String content = refs.isEmpty() ? NO_EVIDENCE_ANSWER : buildAnswer(userPrompt, refs);
        long costMs = System.currentTimeMillis() - start;
        return LlmResponse.builder()
                .content(content)
                .modelName(model)
                .promptTokens(estimateTokens(safeText(request.getSystemPrompt()) + safeText(request.getUserPrompt())))
                .completionTokens(estimateTokens(content))
                .costMs(costMs)
                .success(true)
                .errorCode(null)
                .errorMessage(null)
                .build();
    }

    /**
     * 演示用流式：先算出完整答案，再切成 ~5 段逐段回调 onToken，让离线 demo 也有
     * 逐字渲染观感。带工具的 agentic 请求交回默认一次性契约（保持 tool_calls 解析）。
     */
    @Override
    public void generateStream(LlmRequest request,
                               java.util.function.Consumer<String> onToken,
                               java.util.function.Consumer<LlmResponse> onDone) {
        if (request != null && request.getTools() != null && !request.getTools().isEmpty()) {
            LlmClient.super.generateStream(request, onToken, onDone);
            return;
        }
        LlmResponse response = generate(request);
        String content = response == null ? null : response.getContent();
        if (StringUtils.hasText(content)) {
            for (String chunk : splitIntoChunks(content, 5)) {
                if (!chunk.isEmpty()) {
                    onToken.accept(chunk);
                }
            }
        }
        onDone.accept(response);
    }

    /**
     * 带工具的演示用流式：
     * - 带工具且无历史工具结果：通知一次工具调用开始，再给出完整 tool_calls 响应。
     * - 最终答案轮：切成 ~5 段逐段回调 onContentToken，再 onDone。
     * 保持与 generateAgentic 行为一致，确保 agentic loop 可正常运转。
     */
    @Override
    public void generateStreamWithTools(LlmRequest request,
                                        com.repolens.llm.StreamWithToolsListener listener) {
        // 调用底层 generate 得到结构化结果（MockLlmClient 的 agentic 逻辑）
        LlmResponse response = generate(request);
        if (response == null) {
            listener.onDone(null);
            return;
        }
        // 如果本轮有 tool_calls：通知 onToolCallStart，不拆分 content
        if (response.getToolCalls() != null && !response.getToolCalls().isEmpty()) {
            for (com.repolens.llm.model.ToolCall tc : response.getToolCalls()) {
                if (tc.getName() != null && !tc.getName().isEmpty()) {
                    listener.onToolCallStart(tc.getName());
                }
            }
        } else {
            // 最终答案轮：切片回调，营造逐字效果
            String content = response.getContent();
            if (StringUtils.hasText(content)) {
                for (String chunk : splitIntoChunks(content, 5)) {
                    if (!chunk.isEmpty()) {
                        listener.onContentToken(chunk);
                    }
                }
            }
        }
        listener.onDone(response);
    }

    /** 把文本按字符数近似切成 count 段，最后一段吸收余数。 */
    private List<String> splitIntoChunks(String text, int count) {
        if (count <= 1 || text.length() <= count) {
            return List.of(text);
        }
        List<String> chunks = new ArrayList<>();
        int size = (int) Math.ceil(text.length() / (double) count);
        for (int i = 0; i < text.length(); i += size) {
            chunks.add(text.substring(i, Math.min(text.length(), i + size)));
        }
        return chunks;
    }

    /**
     * 模拟 agentic 决策：
     * - 若历史里还没有任何工具结果（role=tool），返回一个 searchCodeChunks 工具调用；
     * - 一旦看到工具结果，就给出最终答案并停止（toolCalls 为空 = loop 终止）。
     */
    private LlmResponse generateAgentic(LlmRequest request, long start) {
        boolean hasToolResult = request.getMessages() != null && request.getMessages().stream()
                .anyMatch(m -> "tool".equals(m.getRole()));
        String model = StringUtils.hasText(request.getModelName()) ? request.getModelName() : llmRuntimeConfig.getModelName();

        if (!hasToolResult) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("query", extractLatestUserText(request));
            args.put("topK", 5);
            ToolCall call = ToolCall.builder()
                    .id("mock-call-1")
                    .name("searchCodeChunks")
                    .arguments(args)
                    .build();
            return LlmResponse.builder()
                    .content("")
                    .modelName(model)
                    .promptTokens(8)
                    .completionTokens(0)
                    .costMs(System.currentTimeMillis() - start)
                    .success(true)
                    .toolCalls(List.of(call))
                    .finishReason("tool_calls")
                    .build();
        }

        String content = "已根据工具检索到的证据完成分析（mock agent）。请以引用的文件路径和行号为准。";
        return LlmResponse.builder()
                .content(content)
                .modelName(model)
                .promptTokens(16)
                .completionTokens(estimateTokens(content))
                .costMs(System.currentTimeMillis() - start)
                .success(true)
                .toolCalls(List.of())
                .finishReason("stop")
                .build();
    }

    private String extractLatestUserText(LlmRequest request) {
        if (request.getMessages() != null) {
            for (int i = request.getMessages().size() - 1; i >= 0; i--) {
                LlmMessage m = request.getMessages().get(i);
                if ("user".equals(m.getRole()) && StringUtils.hasText(m.getContent())) {
                    return m.getContent();
                }
            }
        }
        return safeText(request.getUserPrompt());
    }

    private String buildAnswer(String userPrompt, List<EvidenceRef> refs) {
        StringBuilder answer = new StringBuilder();
        String normalizedPrompt = userPrompt == null ? "" : userPrompt.toLowerCase(Locale.ROOT);
        if (normalizedPrompt.contains("createuser") || normalizedPrompt.contains("创建用户")) {
            answer.append("可定位到用户创建入口 `UserController#createUser`。\n");
        }
        answer.append("基于当前仓库证据，相关实现位置如下：\n");
        int limit = Math.min(3, refs.size());
        for (int i = 0; i < limit; i++) {
            EvidenceRef ref = refs.get(i);
            answer.append(i + 1)
                    .append(". ")
                    .append(ref.filePath)
                    .append(" [")
                    .append(ref.filePath)
                    .append(":")
                    .append(ref.startLine)
                    .append("-")
                    .append(ref.endLine)
                    .append("]");
            if (StringUtils.hasText(ref.chunkType)) {
                answer.append(" (").append(ref.chunkType.toUpperCase(Locale.ROOT)).append(")");
            }
            answer.append("\n");
        }
        answer.append("请以上述引用为准，如需更精确结论可继续指定类名或方法名。");
        return answer.toString();
    }

    private List<EvidenceRef> parseEvidenceRefs(String prompt) {
        if (!StringUtils.hasText(prompt)) {
            return List.of();
        }
        String[] lines = prompt.split("\\R");
        List<EvidenceRef> refs = new ArrayList<>();
        EvidenceRef current = null;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[Evidence-")) {
                if (current != null && StringUtils.hasText(current.filePath)) {
                    refs.add(current);
                }
                current = new EvidenceRef();
                continue;
            }
            if (current == null) {
                continue;
            }
            if (trimmed.startsWith("filePath:")) {
                current.filePath = trimmed.substring("filePath:".length()).trim();
            } else if (trimmed.startsWith("chunkType:")) {
                current.chunkType = trimmed.substring("chunkType:".length()).trim();
            } else if (trimmed.startsWith("lines:")) {
                String lineRange = trimmed.substring("lines:".length()).trim();
                String[] parts = lineRange.split("-");
                if (parts.length == 2) {
                    current.startLine = safeParseLine(parts[0]);
                    current.endLine = safeParseLine(parts[1]);
                }
            }
        }
        if (current != null && StringUtils.hasText(current.filePath)) {
            refs.add(current);
        }
        return refs;
    }

    private int safeParseLine(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception ex) {
            return 0;
        }
    }

    private int estimateTokens(String text) {
        if (!StringUtils.hasText(text)) {
            return 0;
        }
        return Math.max(1, text.length() / 4);
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private static class EvidenceRef {
        private String filePath;
        private String chunkType;
        private int startLine;
        private int endLine;
    }
}
