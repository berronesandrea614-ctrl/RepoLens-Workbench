package com.repolens.service.support.context;

import com.repolens.domain.entity.SessionContextNoteEntity;
import com.repolens.llm.model.LlmMessage;
import com.repolens.mapper.SessionContextNoteMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ContextManager {

    private final Tokenizer tokenizer;
    private final LargeOutputStore largeOutputStore;
    private final SessionContextNoteMapper sessionContextNoteMapper;

    @Value("${repolens.context.compact-trigger-ratio:0.92}")
    private double triggerRatio;

    @Value("${repolens.context.compact-target-ratio:0.70}")
    private double targetRatio;

    @Value("${repolens.context.window-tokens.deepseek-chat:65536}")
    private int defaultWindowTokens;

    private static final int COMPRESSED_KEEP_CHARS = 500;
    private static final int KEEP_LAST_TOOL_RESULTS = 3;
    private static final long IDLE_DECAY_MS = 30 * 60 * 1000L; // 30min

    /**
     * 完整 compact 入口：先 L2 时间衰减，再 L1 压缩，最后 L4 激进压缩。
     * @param lastActiveMs 最后一次用户消息的时间戳（0 = 不触发 L2）
     */
    public boolean compact(List<LlmMessage> messages, int windowTokens, long lastActiveMs) {
        compactL2(messages, lastActiveMs);
        return compact(messages, windowTokens);
    }

    public boolean compact(List<LlmMessage> messages, int windowTokens) {
        int window = windowTokens > 0 ? windowTokens : defaultWindowTokens;
        int used = tokenizer.estimateMessages(messages);
        if (used < window * triggerRatio) return false;

        int target = (int) (window * targetRatio);
        log.info("ContextManager: compacting {} tokens → target {}", used, target);

        compactL1(messages, target);
        used = tokenizer.estimateMessages(messages);
        if (used <= target) return true;

        try {
            compactL4(messages, target);
        } catch (Exception e) {
            log.warn("ContextManager: L4 summary failed, staying with L1: {}", e.getMessage());
        }
        return true;
    }

    /**
     * L2：idle > 30min 时，把上次活跃窗口之前的纯思考 assistant 消息降级为占位。
     */
    public void compactL2(List<LlmMessage> messages, long lastActiveMs) {
        if (lastActiveMs <= 0) return;
        long now = System.currentTimeMillis();
        if (now - lastActiveMs < IDLE_DECAY_MS) return;
        for (int i = 1; i < messages.size() - 3; i++) {
            LlmMessage m = messages.get(i);
            if ("assistant".equals(m.getRole())
                    && (m.getToolCalls() == null || m.getToolCalls().isEmpty())
                    && m.getContent() != null && m.getContent().length() > 200) {
                messages.set(i, LlmMessage.builder()
                        .role("assistant")
                        .content("(思考过程已因 idle 衰减)")
                        .build());
            }
        }
        log.debug("ContextManager: L2 decay applied, lastActive={}ms ago", now - lastActiveMs);
    }

    /**
     * L3：把被清理的 tool_result 要点追加到 session_context_note。
     */
    public void appendL3Note(Long sessionId, String content) {
        try {
            if (content == null || content.length() < 100) return;
            String summary = content.substring(0, Math.min(500, content.length()));
            SessionContextNoteEntity note = new SessionContextNoteEntity();
            note.setSessionId(sessionId);
            note.setNoteText(summary);
            note.setCreatedAt(LocalDateTime.now());
            sessionContextNoteMapper.insert(note);
        } catch (Exception e) {
            log.warn("appendL3Note failed (fail-safe): {}", e.getMessage());
        }
    }

    /**
     * 在 seedMessages 开头（system 之后）注入 L3 SUMMARY 消息。
     */
    public void injectL3Summary(List<LlmMessage> messages, Long sessionId) {
        try {
            var notes = sessionContextNoteMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SessionContextNoteEntity>()
                            .eq("session_id", sessionId)
                            .orderByDesc("created_at")
                            .last("LIMIT 10"));
            if (notes.isEmpty()) return;
            String summary = notes.stream()
                    .map(n -> "- " + n.getNoteText())
                    .collect(Collectors.joining("\n"));
            messages.add(1, LlmMessage.builder()
                    .role("user")
                    .content("[CONTEXT SUMMARY]\n" + summary)
                    .build());
        } catch (Exception e) {
            log.warn("injectL3Summary failed (fail-safe): {}", e.getMessage());
        }
    }

    private void compactL1(List<LlmMessage> messages, int target) {
        List<Integer> toolResultIndices = new ArrayList<>();
        for (int i = 1; i < messages.size(); i++) {
            if ("tool".equals(messages.get(i).getRole())) {
                toolResultIndices.add(i);
            }
        }
        int compressUpTo = toolResultIndices.size() - KEEP_LAST_TOOL_RESULTS;
        for (int j = 0; j < compressUpTo; j++) {
            int idx = toolResultIndices.get(j);
            LlmMessage msg = messages.get(idx);
            if (msg.getContent() != null && msg.getContent().length() > COMPRESSED_KEEP_CHARS) {
                String preview = msg.getContent().substring(0, COMPRESSED_KEEP_CHARS);
                messages.set(idx, LlmMessage.builder()
                        .role("tool")
                        .toolCallId(msg.getToolCallId())
                        .content("(为控制上下文长度，此较早观察已压缩) " + preview + "...")
                        .build());
            }
            if (tokenizer.estimateMessages(messages) <= target) return;
        }
    }

    private void compactL4(List<LlmMessage> messages, int target) {
        for (int i = 1; i < messages.size() - 3; i++) {
            LlmMessage msg = messages.get(i);
            if ("tool".equals(msg.getRole())) {
                messages.set(i, LlmMessage.builder()
                        .role("tool")
                        .toolCallId(msg.getToolCallId())
                        .content("(早期工具结果已压缩)")
                        .build());
            }
            if (tokenizer.estimateMessages(messages) <= target) return;
        }
    }
}
