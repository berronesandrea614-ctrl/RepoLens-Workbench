package com.repolens.bridge;

import com.repolens.kernel.ask.AskSpec;
import com.repolens.kernel.ask.AskUserPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * askUser 反问的 bridge 实现（{@link AskUserPort}）——把「agent 提问 → 挂起 → 前端回复 → 唤醒」这条
 * 带内 request-response 架在流式 SSE 之上。
 *
 * <p>通道按 sessionId 绑定：{@link #bind} 在 {@code answerStream} 拿到 emitter 后登记「怎么把问题 emit 给前端」，
 * {@link #ask} 沿它推问题、再阻塞等前端经 {@link #answer} 回传的回复。三条保守策略防止 agent 卡死或滥问：
 * 单会话每 run 上限 {@value #MAX_ASKS_PER_SESSION} 次、等待上限 {@value #WAIT_SECONDS}s 超时、无通道即降级——
 * 任一触发都返回引导 agent「自主继续」的文本而非阻塞。
 */
@Service
public class AskUserService implements AskUserPort {

    private static final Logger log = LoggerFactory.getLogger(AskUserService.class);

    /** 单会话每次运行的提问上限（防止 agent 拿它当每步确认开关反复打断）。 */
    private static final int MAX_ASKS_PER_SESSION = 3;
    /** 等待用户回复的上限秒数（超时保守继续，不永久挂住 loop 线程与 SSE）。 */
    private static final long WAIT_SECONDS = 240;

    /** sessionId → 把 AskEvent 推给前端的通道（emit "ask" SSE）。 */
    private final Map<Long, Consumer<AskEvent>> channels = new ConcurrentHashMap<>();
    /** sessionId → 本次运行已提问次数。 */
    private final Map<Long, AtomicInteger> askCounts = new ConcurrentHashMap<>();
    /** questionId → 回复交接队列（提问线程 take，回复线程 offer）。 */
    private final Map<String, SynchronousQueue<String>> pending = new ConcurrentHashMap<>();

    /**
     * 一次提问事件（emit 给前端的 payload，Jackson 序列化成 JSON）。
     *
     * @param questionId 问题 id（回复回传时带上）
     * @param questions  结构化问题列表（前端渲染成可点选的多选卡片）
     * @param summary    首个问题文本（纯文本兜底/日志用）
     */
    public record AskEvent(String questionId, List<AskSpec.Question> questions, String summary) {
    }

    /** answerStream 拿到 emitter 后绑定：sessionId → 如何 emit ask 事件。重置本会话提问计数。 */
    public void bind(Long sessionId, Consumer<AskEvent> channel) {
        if (sessionId == null || channel == null) {
            return;
        }
        channels.put(sessionId, channel);
        askCounts.put(sessionId, new AtomicInteger(0));
    }

    /** run 结束/失败时解绑：清掉通道与计数，并唤醒该会话仍在挂起的提问（保守继续）。 */
    public void unbind(Long sessionId) {
        if (sessionId == null) {
            return;
        }
        channels.remove(sessionId);
        askCounts.remove(sessionId);
        // 唤醒可能还挂着的 pending（前缀匹配本会话），避免 loop 线程泄漏。
        String prefix = sessionId + "#";
        for (Map.Entry<String, SynchronousQueue<String>> e : pending.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                e.getValue().offer("");
            }
        }
    }

    @Override
    public String ask(Long sessionId, AskSpec spec) {
        Consumer<AskEvent> channel = sessionId == null ? null : channels.get(sessionId);
        if (channel == null) {
            return "（当前环境无法向用户提问，请按你判断最合理的方案继续。）";
        }
        if (spec == null || spec.questions() == null || spec.questions().isEmpty()) {
            return "（没有可问的问题，请按最合理的方案继续。）";
        }
        AtomicInteger count = askCounts.computeIfAbsent(sessionId, k -> new AtomicInteger(0));
        int n = count.incrementAndGet();
        if (n > MAX_ASKS_PER_SESSION) {
            return "（本次运行的提问次数已达上限，请不要再问，按你判断最合理的方案自主继续到完成。）";
        }

        String questionId = sessionId + "#" + n;
        String summary = spec.questions().get(0).question();
        SynchronousQueue<String> queue = new SynchronousQueue<>();
        pending.put(questionId, queue);
        try {
            channel.accept(new AskEvent(questionId, spec.questions(), summary));
            String reply = queue.poll(WAIT_SECONDS, TimeUnit.SECONDS);
            if (reply == null) {
                return "（用户未在时限内回复，请按你判断最合理的方案继续。）";
            }
            if (reply.isBlank()) {
                // 空回复（含 unbind 唤醒）：视为不额外指示，继续。
                return "（用户未给出进一步指示，请按最合理的方案继续。）";
            }
            return "用户回复：" + reply;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "（提问被中断，请按最合理的方案继续。）";
        } catch (Exception e) {
            log.warn("[askUser] 提问失败 sessionId={}", sessionId, e);
            return "（向用户提问失败，请按最合理的方案继续。）";
        } finally {
            pending.remove(questionId);
        }
    }

    /** 前端回传回复：把 reply 交给对应挂起的提问线程。返回是否成功交接（无对应待答问题则 false）。 */
    public boolean answer(String questionId, String reply) {
        if (questionId == null) {
            return false;
        }
        SynchronousQueue<String> queue = pending.get(questionId);
        if (queue == null) {
            return false;
        }
        try {
            return queue.offer(reply == null ? "" : reply, 5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
