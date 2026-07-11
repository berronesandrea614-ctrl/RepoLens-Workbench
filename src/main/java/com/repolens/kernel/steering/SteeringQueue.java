package com.repolens.kernel.steering;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Steering 队列（M7.2 中途插话重定向）：让用户在一个正在运行的 agent run <b>中途</b>插入一条消息，
 * 由 {@link com.repolens.kernel.loop.AgentLoopExecutor} 在<b>下一轮循环开始前排空</b>并注入上下文，
 * 使 agent 据此重定向——<b>而不重启 loop</b>（turns 连续、同一 run）。
 *
 * <p>线程安全：push 通常来自外部线程（如 HTTP 控制端点 / 前端插话），drain 来自 loop 线程，
 * 用 {@link ConcurrentLinkedQueue} 承接跨线程投递。按 (sessionId, runId) 分桶隔离不同 run。
 *
 * <p>与「无 tool_call 即止」正交：steering 只是往消息列表追加 user 消息，不改终止判据；
 * 若插话让 agent 又想调工具，它自然会在下一轮给出 tool_call，loop 照常继续。
 */
@Component("kernelSteeringQueue")
public class SteeringQueue {

    private static final Logger log = LoggerFactory.getLogger(SteeringQueue.class);

    /** key = sessionId + "#" + runId，value = 该 run 待注入的消息队列。 */
    private final Map<String, Queue<String>> queues = new ConcurrentHashMap<>();

    /**
     * 往某个正在运行（或即将运行）的 run 中途插入一条重定向消息。
     *
     * @param sessionId 会话 id（可空）
     * @param runId     run id（可空）
     * @param message   插话内容（空白忽略）
     */
    public void push(Long sessionId, Long runId, String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        String key = key(sessionId, runId);
        queues.computeIfAbsent(key, k -> new ConcurrentLinkedQueue<>()).add(message);
        log.info("[steering] push 一条插话到 run {}（当前待注入 {} 条）", key, queues.get(key).size());
    }

    /**
     * 排空某个 run 的待注入消息（loop 每轮开始前调）。返回后队列清空。
     *
     * @return 按投递顺序的待注入消息（无则空列表）
     */
    public List<String> drain(Long sessionId, Long runId) {
        Queue<String> q = queues.get(key(sessionId, runId));
        if (q == null || q.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        String m;
        while ((m = q.poll()) != null) {
            out.add(m);
        }
        if (!out.isEmpty()) {
            log.info("[steering] drain run {}#{}：注入 {} 条插话", sessionId, runId, out.size());
        }
        return out;
    }

    /** 是否有待注入消息（不消费）。 */
    public boolean hasPending(Long sessionId, Long runId) {
        Queue<String> q = queues.get(key(sessionId, runId));
        return q != null && !q.isEmpty();
    }

    private static String key(Long sessionId, Long runId) {
        return sessionId + "#" + runId;
    }
}
