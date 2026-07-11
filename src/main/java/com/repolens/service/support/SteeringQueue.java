package com.repolens.service.support;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Component
public class SteeringQueue {
    private final Map<Long, ConcurrentLinkedQueue<String>> queues = new ConcurrentHashMap<>();

    public void push(Long runId, String text) {
        queues.computeIfAbsent(runId, k -> new ConcurrentLinkedQueue<>()).add(text);
    }

    public List<String> drain(Long runId) {
        var q = queues.get(runId);
        if (q == null) return List.of();
        var msgs = new ArrayList<String>();
        String m;
        while ((m = q.poll()) != null) msgs.add(m);
        return msgs;
    }

    public void clear(Long runId) {
        queues.remove(runId);
    }
}
