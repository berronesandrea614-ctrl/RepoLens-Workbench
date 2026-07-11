package com.repolens.service.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.domain.entity.AgentRunPlanEntity;
import com.repolens.domain.vo.TodoItemVO;
import com.repolens.mapper.AgentRunPlanMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentControlToolHandler {

    private final TodoState todoState;
    private final ObjectMapper objectMapper;
    private final AgentRunPlanMapper agentRunPlanMapper;

    @SuppressWarnings("unchecked")
    public String handleTodoWrite(Long runId, Map<String, Object> args) {
        try {
            Object contentObj = args.get("content");
            if (!(contentObj instanceof List)) {
                return "TodoWrite error: content 必须是数组";
            }
            List<Map<String, Object>> rawItems = (List<Map<String, Object>>) contentObj;
            List<TodoItemVO> items = new ArrayList<>();
            for (Map<String, Object> raw : rawItems) {
                TodoItemVO item = new TodoItemVO();
                item.setId(String.valueOf(raw.getOrDefault("id", java.util.UUID.randomUUID().toString())));
                item.setContent(String.valueOf(raw.getOrDefault("content", "")));
                item.setStatus(String.valueOf(raw.getOrDefault("status", "pending")));
                item.setActiveForm((String) raw.get("activeForm"));
                items.add(item);
            }
            long inProgressCount = items.stream()
                    .filter(i -> "in_progress".equals(i.getStatus())).count();
            if (inProgressCount != 1) {
                return "TodoWrite rejected: 必须恰好有 1 个 in_progress（当前 " + inProgressCount + " 个）。请修正后重试。";
            }
            List<TodoItemVO> prev = todoState.get(runId);
            for (TodoItemVO prevItem : prev) {
                if ("completed".equals(prevItem.getStatus())) {
                    String prevId = prevItem.getId();
                    items.stream()
                            .filter(i -> prevId.equals(i.getId()))
                            .findFirst()
                            .ifPresent(cur -> {
                                if (!"completed".equals(cur.getStatus())) {
                                    throw new IllegalStateException(
                                            "completed 任务 " + prevId + " 不能回退到 " + cur.getStatus());
                                }
                            });
                }
            }
            todoState.set(runId, items);
            try {
                String todoJson = objectMapper.writeValueAsString(items);
                upsertTodoJson(runId, todoJson);
            } catch (Exception e) {
                log.warn("TodoWrite: upsert to DB failed (fail-safe): {}", e.getMessage());
            }
            TodoItemVO current = items.stream()
                    .filter(i -> "in_progress".equals(i.getStatus()))
                    .findFirst().orElse(null);
            TodoItemVO nextPending = items.stream()
                    .filter(i -> "pending".equals(i.getStatus()))
                    .findFirst().orElse(null);
            if (current == null && nextPending == null) {
                long completedCount = items.stream().filter(i -> "completed".equals(i.getStatus())).count();
                if (completedCount == items.size() && !items.isEmpty()) {
                    return "TodoWrite updated. 所有 " + items.size() + " 项任务均已完成。请给出最终答案总结。";
                }
                return "TodoWrite updated. 当前进行中：—。请创建新的 in_progress 项继续工作，或给出最终答案。";
            }
            return "TodoWrite updated. 当前进行中：" +
                    (current != null ? current.getContent() : "—") +
                    "。下一步：" + (nextPending != null ? nextPending.getContent() : "（无）") +
                    "。完成当前步骤后，同一次 TodoWrite 标为 completed 并推进到下一项。";
        } catch (IllegalStateException ise) {
            return "TodoWrite rejected: " + ise.getMessage();
        } catch (Exception e) {
            log.warn("handleTodoWrite failed: {}", e.getMessage());
            return "TodoWrite error: " + e.getMessage();
        }
    }

    private void upsertTodoJson(Long runId, String todoJson) {
        try {
            var existing = agentRunPlanMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<AgentRunPlanEntity>()
                            .eq("agent_run_id", runId));
            if (existing != null) {
                existing.setTodoJson(todoJson);
                agentRunPlanMapper.updateById(existing);
            } else {
                AgentRunPlanEntity newPlan = new AgentRunPlanEntity();
                newPlan.setAgentRunId(runId);
                newPlan.setTodoJson(todoJson);
                agentRunPlanMapper.insert(newPlan);
            }
        } catch (Exception e) {
            log.warn("TodoWrite upsertTodoJson failed: {}", e.getMessage());
        }
    }

    public List<TodoItemVO> getTodoState(Long runId) {
        return todoState.get(runId);
    }
}
