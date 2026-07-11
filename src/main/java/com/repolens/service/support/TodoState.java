package com.repolens.service.support;

import com.repolens.domain.vo.TodoItemVO;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TodoState {
    private final Map<Long, List<TodoItemVO>> state = new ConcurrentHashMap<>();

    public void set(Long runId, List<TodoItemVO> items) {
        state.put(runId, items);
    }

    public List<TodoItemVO> get(Long runId) {
        return state.getOrDefault(runId, List.of());
    }

    public void clear(Long runId) {
        state.remove(runId);
    }
}
