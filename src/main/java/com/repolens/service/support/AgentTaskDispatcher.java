package com.repolens.service.support;

import com.repolens.llm.LlmClient;
import com.repolens.llm.model.LlmMessage;
import com.repolens.llm.model.ToolDefinition;
import com.repolens.service.impl.support.AgentLoopExecutor;
import com.repolens.service.impl.support.AgentToolCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentTaskDispatcher {

    // @Lazy 打破旧 god class 的构造器循环（AgentLoopExecutor ↔ AgentTaskDispatcher）。
    // 经 lombok.config 的 copyableAnnotations 复制到构造器参数，注入惰性代理。
    @Lazy
    private final AgentLoopExecutor agentLoopExecutor;
    private final AgentToolCatalog agentToolCatalog;

    private static final int MAX_ANSWER_CHARS = 2000;

    public String dispatchTask(Long userId, Long repoId, Long sessionId,
                               Long parentRunId, Map<String, Object> args) {
        try {
            String description = (String) args.getOrDefault("description", "子代理任务");
            String prompt = (String) args.getOrDefault("prompt", "");
            if (prompt == null || prompt.isBlank()) {
                return "Task error: prompt 不能为空";
            }
            String subagentSystem = "你是只读分析子代理。只使用只读工具。不调用 Task 工具（防套娃）。"
                    + "直接返回分析结果，不需要客套话。";
            List<LlmMessage> seed = List.of(
                    LlmMessage.builder().role("system").content(subagentSystem).build(),
                    LlmMessage.builder().role("user").content(prompt).build()
            );
            List<ToolDefinition> readOnlyTools = agentToolCatalog.baseReadOnlyTools();
            AgentLoopExecutor.AgentResult result = agentLoopExecutor.run(
                    userId, repoId, sessionId, seed, readOnlyTools,
                    null, 5, 30_000);
            String answer = result.getAnswer();
            if (answer != null && answer.length() > MAX_ANSWER_CHARS) {
                answer = answer.substring(0, MAX_ANSWER_CHARS) + "\n... (已截断)";
            }
            return "子代理「" + description + "」完成：\n" + (answer != null ? answer : "（无结果）");
        } catch (Exception e) {
            log.warn("AgentTaskDispatcher failed: {}", e.getMessage());
            return "Task error: " + e.getMessage();
        }
    }
}
