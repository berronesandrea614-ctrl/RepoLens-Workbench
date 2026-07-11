package com.repolens.service.support;

import com.repolens.mapper.AgentRunPlanMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentControlToolHandlerTest {

    TodoState todoState = new TodoState();
    AgentControlToolHandler handler = new AgentControlToolHandler(
            todoState,
            new com.fasterxml.jackson.databind.ObjectMapper(),
            Mockito.mock(AgentRunPlanMapper.class)
    );

    @Test
    void validTodo_accepted() {
        Map<String, Object> args = Map.of("content", List.of(
                Map.of("id", "1", "content", "任务A", "status", "in_progress"),
                Map.of("id", "2", "content", "任务B", "status", "pending")
        ));
        String result = handler.handleTodoWrite(1L, args);
        assertThat(result).contains("当前进行中");
        assertThat(todoState.get(1L)).hasSize(2);
    }

    @Test
    void twoInProgress_rejected() {
        Map<String, Object> args = Map.of("content", List.of(
                Map.of("id", "1", "content", "A", "status", "in_progress"),
                Map.of("id", "2", "content", "B", "status", "in_progress")
        ));
        String result = handler.handleTodoWrite(1L, args);
        assertThat(result).contains("rejected");
    }

    @Test
    void completedRollback_rejected() {
        com.repolens.domain.vo.TodoItemVO v = new com.repolens.domain.vo.TodoItemVO();
        v.setId("1");
        v.setContent("A");
        v.setStatus("completed");
        todoState.set(1L, List.of(v));
        Map<String, Object> args = Map.of("content", List.of(
                Map.of("id", "1", "content", "A", "status", "pending"),
                Map.of("id", "2", "content", "B", "status", "in_progress")
        ));
        String result = handler.handleTodoWrite(1L, args);
        assertThat(result).contains("rejected");
    }
}
